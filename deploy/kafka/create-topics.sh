#!/bin/bash
# Crea todos los topics y sus DLT: 3 particiones, RF 1 (SPEC §7.1).
set -euo pipefail

for topic in inventory.commands inventory.replies credit.commands credit.replies shipping.commands shipping.replies; do
  for name in "$topic" "$topic.DLT"; do
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists \
      --topic "$name" --partitions 3 --replication-factor 1
  done
done
touch /tmp/topics-ready
