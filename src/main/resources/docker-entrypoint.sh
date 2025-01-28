#!/bin/bash
set -e

echo "Starting Flink $1"
echo "JAVA_HOME=$JAVA_HOME"
echo "PATH=$PATH"
java -version

if [ "$1" = "jobmanager" ]; then
    echo "Starting Job Manager..."
    exec "$FLINK_HOME/bin/jobmanager.sh" start-foreground
elif [ "$1" = "taskmanager" ]; then
    echo "Starting Task Manager..."
    exec "$FLINK_HOME/bin/taskmanager.sh" start-foreground
else
    echo "Unknown command $1"
    exit 1
fi
