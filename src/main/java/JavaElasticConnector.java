import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.GlobalWindows;
import org.apache.flink.streaming.api.windowing.triggers.CountTrigger;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.GlobalWindow;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

public class JavaElasticConnector {
    private static final Logger LOG = LoggerFactory.getLogger(JavaElasticConnector.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Properties CONFIG = loadConfig();
    
    // Configuration constants loaded from properties
    private static final int BATCH_SIZE = Integer.parseInt(CONFIG.getProperty("flink.trigger.batch.size", "5"));
    private static final int MAX_RETRIES = Integer.parseInt(CONFIG.getProperty("elasticsearch.max.retries", "3"));
    private static final long TIMEOUT_MS = Long.parseLong(CONFIG.getProperty("flink.window.timeout.ms", "3000"));
    private static final String MAPPING_DIRECTORY = CONFIG.getProperty("elasticsearch.mapping.directory", "mappings");
    private static final String MAPPING_FILE_SUFFIX = CONFIG.getProperty("elasticsearch.mapping.file.suffix", "_mapping.json");
    private static final Set<String> REQUIRED_FIELDS = new HashSet<>(
        Arrays.asList(CONFIG.getProperty("elasticsearch.required.fields", "name,capital,population").split(","))
    );

    private static Properties loadConfig() {
        Properties configProps = new Properties();
        try (InputStream input = JavaElasticConnector.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input == null) {
                LOG.error("application.properties not found, using defaults");
                configProps = getDefaultProperties();
            } else {
                configProps.load(input);
                Properties defaults = getDefaultProperties();
                for (String key : defaults.stringPropertyNames()) {
                    if (!configProps.containsKey(key)) {
                        configProps.setProperty(key, defaults.getProperty(key));
                    }
                }
            }
            return configProps;
        } catch (IOException ex) {
            LOG.error("Error reading application.properties: {}", ex.getMessage());
            return getDefaultProperties();
        }
    }

    private static Properties getDefaultProperties() {
        Properties defaults = new Properties();
        // Docker-compatible default values
        defaults.setProperty("kafka.bootstrap.servers", "kafka:9092");
        defaults.setProperty("kafka.consumer.group.id", "flink-group-fixed");
        defaults.setProperty("kafka.consumer.auto.offset.reset", "earliest");
        defaults.setProperty("kafka.consumer.enable.auto.commit", "false");
        defaults.setProperty("kafka.consumer.isolation.level", "read_committed");
        defaults.setProperty("elasticsearch.host", "elasticsearch");
        defaults.setProperty("elasticsearch.port", "9200");
        defaults.setProperty("elasticsearch.bulk.size", "5");
        defaults.setProperty("elasticsearch.max.retries", "3");
        defaults.setProperty("elasticsearch.connection.timeout", "5000");
        defaults.setProperty("elasticsearch.socket.timeout", "60000");
        defaults.setProperty("flink.checkpoint.interval", "1000");
        defaults.setProperty("flink.parallelism", "1");
        defaults.setProperty("flink.trigger.batch.size", "5");
        defaults.setProperty("flink.window.timeout.ms", "3000");
        defaults.setProperty("elasticsearch.mapping.directory", "mappings");
        defaults.setProperty("elasticsearch.mapping.file.suffix", "_mapping.json");
        defaults.setProperty("elasticsearch.required.fields", "name,capital,population");
        defaults.setProperty("logging.message.received", "false");
        defaults.setProperty("logging.message.processed", "false");
        defaults.setProperty("logging.message.error", "true");
        return defaults;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: JavaElasticConnector <kafka_topic> <mapping_type>");
            System.exit(1);
        }

        String topic = args[0];
        String mappingType = args[1];
        String indexName = mappingType.toLowerCase();

        // Debug logging
        System.out.println("CONFIG loaded: " + CONFIG);
        System.out.println("kafka.bootstrap.servers: " + CONFIG.getProperty("kafka.bootstrap.servers"));
        System.out.println("kafka.consumer.group.id: " + CONFIG.getProperty("kafka.consumer.group.id"));

        // Setup Flink environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Configure Flink environment with default values if properties are missing
        env.enableCheckpointing(Long.parseLong(CONFIG.getProperty("flink.checkpoint.interval", "1000")));
        env.setParallelism(Integer.parseInt(CONFIG.getProperty("flink.parallelism", "1")));

        // Enable metrics
        env.getConfig().setGlobalJobParameters(new Configuration());

        // Configure Kafka source
        KafkaSource<String> source = KafkaSource.<String>builder()
            .setBootstrapServers(CONFIG.getProperty("kafka.bootstrap.servers"))
            .setTopics(topic)
            .setGroupId(CONFIG.getProperty("kafka.consumer.group.id"))
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new DeserializationSchema<String>() {
                @Override
                public String deserialize(byte[] message) {
                    return new String(message);
                }

                @Override
                public boolean isEndOfStream(String nextElement) {
                    return false;
                }

                @Override
                public TypeInformation<String> getProducedType() {
                    return TypeInformation.of(String.class);
                }
            })
            .build();

        // Create index with mapping
        createIndexWithMapping(indexName, mappingType);

        // Create the processing pipeline
        DataStream<String> stream = env
            .fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka Source")
            .name("kafka-source")
            .map(new MessageReceivedMapper())
            .map(new MessageProcessingMapper())
            .filter(message -> message != null) // Filter out null messages (invalid JSON)
            .name("json-filter")
            .map(new MessageProcessingMapper())
            .name("message-processing");

        // Using Global window with custom trigger for batch processing
        stream
            .windowAll(GlobalWindows.create())
            .trigger(new Trigger<String, GlobalWindow>() {
                private static final long serialVersionUID = 1L;

                @Override
                public TriggerResult onElement(String element, long timestamp, GlobalWindow window, TriggerContext ctx) throws Exception {
                    // Register timer for timeout
                    ctx.registerProcessingTimeTimer(ctx.getCurrentProcessingTime() + TIMEOUT_MS);
                    
                    ValueStateDescriptor<Long> countDesc = new ValueStateDescriptor<>("count", Long.class);
                    Long count = ctx.getPartitionedState(countDesc).value();
                    if (count == null) {
                        count = 0L;
                    }
                    count++;
                    ctx.getPartitionedState(countDesc).update(count);
                    if (count >= BATCH_SIZE) {
                        ctx.getPartitionedState(countDesc).clear();
                        return TriggerResult.FIRE_AND_PURGE;
                    }
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onProcessingTime(long time, GlobalWindow window, TriggerContext ctx) throws Exception {
                    ValueStateDescriptor<Long> countDesc = new ValueStateDescriptor<>("count", Long.class);
                    Long count = ctx.getPartitionedState(countDesc).value();
                    if (count != null && count > 0) {
                        ctx.getPartitionedState(countDesc).clear();
                        return TriggerResult.FIRE_AND_PURGE;
                    }
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onEventTime(long time, GlobalWindow window, TriggerContext ctx) throws Exception {
                    return TriggerResult.CONTINUE;
                }

                @Override
                public void clear(GlobalWindow window, TriggerContext ctx) throws Exception {
                    ValueStateDescriptor<Long> countDesc = new ValueStateDescriptor<>("count", Long.class);
                    ctx.getPartitionedState(countDesc).clear();
                }
            })
            .process(new BulkWindowFunction(indexName, topic))
            .name("elasticsearch-sink");

        env.execute("Kafka to Elasticsearch Flink Job");
    }

    // Message Received Metrics
    private static class MessageReceivedMapper extends RichMapFunction<String, String> {
        private transient Counter messageCounter;
        private boolean logReceived;

        @Override
        public void open(Configuration parameters) {
            messageCounter = getRuntimeContext()
                .getMetricGroup()
                .counter("messages-received");
            logReceived = Boolean.parseBoolean(CONFIG.getProperty("logging.message.received", "false"));
        }

        @Override
        public String map(String value) {
            messageCounter.inc();
            if (logReceived) {
                LOG.info("Received message: {}", value);
            }
            return value;
        }
    }

    // Message Processing and Validation
    private static class MessageProcessingMapper extends RichMapFunction<String, String> {
        private transient Counter processedCounter;
        private transient Counter invalidJsonCounter;
        private transient Counter invalidSchemaCounter;
        private boolean logProcessed;
        private boolean logError;
        private transient ObjectMapper objectMapper;
        private Set<String> allowedFields;

        @Override
        public void open(Configuration parameters) {
            processedCounter = getRuntimeContext()
                .getMetricGroup()
                .counter("messages-processed");
            invalidJsonCounter = getRuntimeContext()
                .getMetricGroup()
                .counter("invalid-json-messages");
            invalidSchemaCounter = getRuntimeContext()
                .getMetricGroup()
                .counter("invalid-schema-messages");
            logProcessed = Boolean.parseBoolean(CONFIG.getProperty("logging.message.processed", "false"));
            logError = Boolean.parseBoolean(CONFIG.getProperty("logging.message.error", "true"));
            objectMapper = new ObjectMapper();
            
            allowedFields = new HashSet<>(Arrays.asList(
                CONFIG.getProperty("elasticsearch.required.fields").split(",")
            ));
        }

        @Override
        public String map(String value) {
            try {
                JsonNode jsonNode = objectMapper.readTree(value);
                if (!jsonNode.isObject()) {
                    if (logError) {
                        LOG.error("Invalid JSON: not an object");
                    }
                    invalidJsonCounter.inc();
                    return null;
                }

                // Check for unknown fields
                ObjectNode objectNode = (ObjectNode) jsonNode;
                Iterator<String> fieldNames = objectNode.fieldNames();
                boolean hasUnknownFields = false;
                StringBuilder unknownFieldsList = new StringBuilder();
                while (fieldNames.hasNext()) {
                    String field = fieldNames.next();
                    if (!allowedFields.contains(field)) {
                        hasUnknownFields = true;
                        if (unknownFieldsList.length() > 0) {
                            unknownFieldsList.append(", ");
                        }
                        unknownFieldsList.append(field);
                    }
                }

                if (hasUnknownFields) {
                    if (logError) {
                        LOG.error("Unknown fields found: {}", unknownFieldsList.toString());
                    }
                    invalidSchemaCounter.inc();
                    return null;
                }

                // Validate required fields and types
                if (!validateSchema(objectNode)) {
                    invalidSchemaCounter.inc();
                    return null;
                }

                String processedValue = objectMapper.writeValueAsString(objectNode);
                processedCounter.inc();
                if (logProcessed) {
                    LOG.info("Message processed successfully");
                }
                return processedValue;
            } catch (Exception e) {
                if (logError) {
                    LOG.error("Invalid JSON: {}", e.getMessage());
                }
                invalidJsonCounter.inc();
                return null;
            }
        }

        private boolean validateSchema(ObjectNode node) {
            JsonNode nameNode = node.get("name");
            JsonNode capitalNode = node.get("capital");
            JsonNode populationNode = node.get("population");

            if (nameNode == null || !nameNode.isTextual()) {
                if (logError) LOG.error("Invalid or missing 'name' field");
                return false;
            }
            if (capitalNode == null || !capitalNode.isTextual()) {
                if (logError) LOG.error("Invalid or missing 'capital' field");
                return false;
            }
            if (populationNode == null || !populationNode.isNumber()) {
                if (logError) LOG.error("Invalid or missing 'population' field");
                return false;
            }

            return true;
        }
    }

    // Elasticsearch Bulk Processor
    private static class BulkWindowFunction extends ProcessAllWindowFunction<String, Object, GlobalWindow> {
        private static final long serialVersionUID = 1L;
        private final String indexName;
        private final String sourceTopic;
        private transient RestClient restClient;
        private transient ElasticsearchTransport transport;
        private transient ElasticsearchClient client;
        private transient Counter successCounter;
        private transient Counter failureCounter;

        public BulkWindowFunction(String indexName, String sourceTopic) {
            this.indexName = indexName;
            this.sourceTopic = sourceTopic;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            // Initialize counters
            successCounter = getRuntimeContext().getMetricGroup().counter("bulk_success");
            failureCounter = getRuntimeContext().getMetricGroup().counter("bulk_failures");

            // Initialize Elasticsearch client
            restClient = RestClient.builder(new HttpHost(CONFIG.getProperty("elasticsearch.host"), 
                Integer.parseInt(CONFIG.getProperty("elasticsearch.port"))))
                .setRequestConfigCallback(builder -> builder
                    .setConnectTimeout(Integer.parseInt(CONFIG.getProperty("elasticsearch.connection.timeout", "5000")))
                    .setSocketTimeout(Integer.parseInt(CONFIG.getProperty("elasticsearch.socket.timeout", "60000"))))
                .build();

            transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            client = new ElasticsearchClient(transport);
        }

        @Override
        public void close() throws Exception {
            if (transport != null) {
                transport.close();
            }
            if (restClient != null) {
                restClient.close();
            }
        }

        @Override
        public void process(ProcessAllWindowFunction<String, Object, GlobalWindow>.Context context,
                          Iterable<String> elements, Collector<Object> out) throws Exception {
            List<String> batch = new ArrayList<>();
            elements.forEach(batch::add);
            
            if (batch.isEmpty()) {
                return;
            }

            LOG.info("Processing batch of {} messages", batch.size());

            var bulkBuilder = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
            String batchId = UUID.randomUUID().toString();
            long batchTime = System.currentTimeMillis();
            int retryCount = 0;
            boolean success = false;

            while (!success && retryCount < MAX_RETRIES) {
                try {
                    for (String element : batch) {
                        JsonNode jsonNode = new ObjectMapper().readTree(element);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> docMap = new ObjectMapper().convertValue(jsonNode, Map.class);
                        
                        if (docMap != null && !docMap.isEmpty()) {
                            docMap.put("_source_topic", sourceTopic);
                            docMap.put("batch_id", batchId);
                            docMap.put("processed_time", batchTime);
                            String jsonString = new ObjectMapper().writeValueAsString(docMap);
                            
                            // Generate a unique ID if 'name' is not present
                            String documentId = docMap.containsKey("name") ? 
                                docMap.get("name").toString().toLowerCase() :
                                UUID.randomUUID().toString();
                            
                            bulkBuilder.operations(op -> op
                                .index(idx -> idx
                                    .index(indexName)
                                    .id(documentId)
                                    .document(JsonData.fromJson(jsonString))
                                )
                            );
                        }
                    }

                    var bulkResponse = client.bulk(bulkBuilder.build());
                    if (bulkResponse.errors()) {
                        // Log the specific errors
                        bulkResponse.items().forEach(item -> {
                            if (item.error() != null) {
                                LOG.error("Bulk item error: {}", item.error().reason());
                            }
                        });
                        throw new RuntimeException("Bulk indexing had errors");
                    }

                    success = true;
                    successCounter.inc(batch.size());
                    LOG.info("Successfully processed batch of {} messages with ID: {}", batch.size(), batchId);
                } catch (Exception e) {
                    retryCount++;
                    if (retryCount >= MAX_RETRIES) {
                        failureCounter.inc(batch.size());
                        LOG.error("Failed to process batch after {} retries. Error: {}", MAX_RETRIES, e.getMessage());
                        throw new RuntimeException("Failed to execute bulk request after " + MAX_RETRIES + " retries", e);
                    }
                    LOG.warn("Retry {} of {}. Error: {}", retryCount, MAX_RETRIES, e.getMessage());
                    Thread.sleep(1000 * retryCount); // Exponential backoff
                }
            }
        }
    }

    private static void createIndexWithMapping(String indexName, String mappingType) {
        try {
            // Check if index exists
            RestClient restClient = RestClient.builder(new HttpHost(CONFIG.getProperty("elasticsearch.host"), 
                Integer.parseInt(CONFIG.getProperty("elasticsearch.port")))).build();
            ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            ElasticsearchClient esClient = new ElasticsearchClient(transport);

            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                // Read mapping from file
                Map<String, Property> properties = readMappingFile(mappingType);
                
                // Create index with mapping
                CreateIndexResponse response = esClient.indices().create(c -> c
                    .index(indexName)
                    .mappings(m -> m
                        .properties(properties)
                    )
                );

                if (response.acknowledged()) {
                    LOG.info("Created index: {} with mapping type: {}", indexName, mappingType);
                }
            }
        } catch (Exception e) {
            LOG.error("Error creating index: {}", e.getMessage());
            throw new RuntimeException("Failed to create index", e);
        }
    }

    private static Map<String, Property> readMappingFile(String mappingType) throws IOException {
        String resourcePath = CONFIG.getProperty("elasticsearch.mapping.directory") + "/" + 
                            mappingType + CONFIG.getProperty("elasticsearch.mapping.file.suffix");
        try (InputStream inputStream = JavaElasticConnector.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Mapping file not found: " + resourcePath);
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(inputStream);
            JsonNode propertiesNode = rootNode.path("properties");
            return convertJsonToProperties(propertiesNode);
        }
    }

    private static Map<String, Property> convertJsonToProperties(JsonNode propertiesNode) {
        Map<String, Property> properties = new HashMap<>();
        propertiesNode.fields().forEachRemaining(entry -> {
            properties.put(entry.getKey(), convertJsonNodeToProperty(entry.getValue()));
        });
        return properties;
    }

    private static Property convertJsonNodeToProperty(JsonNode fieldConfig) {
        String type = fieldConfig.get("type").asText();

        switch (type) {
            case "text":
                if (fieldConfig.has("fields")) {
                    Map<String, Property> fields = new HashMap<>();
                    fieldConfig.get("fields").fields().forEachRemaining(entry -> {
                        fields.put(entry.getKey(), convertJsonNodeToProperty(entry.getValue()));
                    });
                    return Property.of(p -> p.text(t -> t.fields(fields)));
                }
                return Property.of(p -> p.text(t -> t));
                
            case "keyword":
                return Property.of(p -> p.keyword(k -> k));
                
            case "long":
                return Property.of(p -> p.long_(l -> l));
                
            case "double":
                return Property.of(p -> p.double_(d -> d));
                
            case "date":
                return Property.of(p -> p.date(d -> d));
                
            case "object":
                JsonNode properties = fieldConfig.get("properties");
                if (properties != null) {
                    Map<String, Property> nestedProps = convertJsonToProperties(properties);
                    return Property.of(p -> p.object(o -> o.properties(nestedProps)));
                }
                return Property.of(p -> p.object(o -> o));
                
            default:
                throw new IllegalArgumentException("Unsupported field type: " + type);
        }
    }
}
