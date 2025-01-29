# Commands to run on the terminal to get Flink, Kafka, and Elasticsearch up and running: 
## Stop and Clean Everything

docker-compose down

docker network rm flink-network

docker network prune -f

docker rm -f $(docker ps -aq)

## Start Fresh

docker network create flink-network

docker-compose up -d

Start-Sleep -Seconds 30

## Setup Flink

docker exec flink-jobmanager sh -c "mkdir -p /opt/flink/usrlib"

mvn clean package

docker exec flink-jobmanager sh -c "rm -f /opt/flink/usrlib/my-flink-job.jar"

docker cp target/elastic-kafka-flink-1.0-SNAPSHOT.jar flink-jobmanager:/opt/flink/usrlib/my-flink-job.jar

## Setup Kafka

docker exec kafka kafka-topics.sh --delete --topic test --bootstrap-server kafka:9092 --if-exists

docker exec kafka kafka-topics.sh --create --topic test --bootstrap-server kafka:9092 --partitions 1 --replication-factor 1

docker exec kafka kafka-topics.sh --list --bootstrap-server kafka:9092

## Start Flink Job

docker exec flink-jobmanager flink run -d /opt/flink/usrlib/my-flink-job.jar test countries

## Verify Services

docker ps

curl -X GET "http://localhost:9200/_cat/indices?v"

curl -X GET "http://localhost:8081/jobs/overview"

http://localhost:8081

## Test with Messages

docker exec -it kafka kafka-console-producer.sh --bootstrap-server kafka:9092 --topic test

Paste these messages into the Kafka producer:

json

{"name": "Japan", "capital": "Tokyo", "population": 125836021}

{"name": "Germany", "capital": "Berlin", "population": 83783942}

{"name": "France", "capital": "Paris", "population": 65273511}

{"name": "Italy", "capital": "Rome", "population": 60461826}

{"name": "Spain", "capital": "Madrid", "population": 46754778}

{"name": "United States", "capital": "Washington, D.C.", "population": 331002651}

{"name": "Canada", "capital": "Ottawa", "population": 37742154}

{"name": "Mexico", "capital": "Mexico City", "population": 128932753}

{"name": "Brazil", "capital": "Brasília", "population": 212559417}

{"name": "Argentina", "capital": "Buenos Aires", "population": 45195774}

{"name": "China", "capital": "Beijing", "population": 1402112000}

{"name": "India", "capital": "New Delhi", "population": 1380004385}

{"name": "Australia", "capital": "Canberra", "population": 25687041}

{"name": "Russia", "capital": "Moscow", "population": 144104080}

{"name": "South Africa", "capital": "Pretoria", "population": 59308690}

{"name": "Egypt", "capital": "Cairo", "population": 102334403}

{"name": "United Kingdom", "capital": "London", "population": 67886011}

{"name": "South Korea", "capital": "Seoul", "population": 51269185}

{"name": "Turkey", "capital": "Ankara", "population": 84339067}

{"name": "Indonesia", "capital": "Jakarta", "population": 273523621}

## Verify Results

curl -X GET "http://localhost:9200/countries/_search?pretty"

## Query results

For example docker exec elasticsearch curl -X GET "localhost:9200/countries/_search" -H "Content-Type: application/json" -d '{\"query\":{\"range\":{\"population\":{\"gte\":50000000,\"lte\":200000000}}},\"sort\":[{\"capital.keyword\":{\"order\":\"asc\"}}]}'

## Troubleshooting

docker logs flink-jobmanager

docker logs kafka

docker logs elasticsearch
