/**
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql.datagen;

import io.confluent.connect.avro.AvroData;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.serde.avro.AvroDataTranslator;
import io.confluent.ksql.serde.avro.AvroSchemaTranslator;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.Pair;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

public abstract class DataGenProducer {

  // Max 100 ms between messsages.
  public static final long INTER_MESSAGE_MAX_INTERVAL = 500;

  public void populateTopic(
      final Properties props,
      final Generator generator,
      final String kafkaTopicName,
      final String key,
      final int messageCount,
      long maxInterval,
      final boolean printRows,
      final TimestampGenerator timestampGenerator,
      final TokenBucket tokenBucket
  ) throws InterruptedException {
    final Schema avroSchema = generator.schema();
    if (avroSchema.getField(key) == null) {
      throw new IllegalArgumentException("Key field does not exist:" + key);
    }

    if (maxInterval < 0) {
      maxInterval = INTER_MESSAGE_MAX_INTERVAL;
    }
    
    final AvroData avroData = new AvroData(1);
    org.apache.kafka.connect.data.Schema connectSchema = avroData.toConnectSchema(avroSchema);
    org.apache.kafka.connect.data.Schema ksqlSchema = new AvroSchemaTranslator().toKsqlSchema(connectSchema);

    final Serializer<GenericRow> serializer = getSerializer(avroSchema, ksqlSchema, kafkaTopicName);

    final KafkaProducer<String, GenericRow> producer = new KafkaProducer<>(
        props,
        new StringSerializer(),
        serializer
    );

    final SessionManager sessionManager = new SessionManager();

    int tokens = 0;

    for (int i = 0; i < messageCount; i++) {
      if (tokenBucket != null) {
        if (tokens == 0) {
          tokens = tokenBucket.take(10);
        }
        tokens -= 1;
      }

      final Pair<String, GenericRow> genericRowPair = generateOneGenericRow(
          generator, avroData, avroSchema, ksqlSchema, sessionManager, key);

      final ProducerRecord<String, GenericRow> producerRecord;
      if (timestampGenerator == null) {
        producerRecord = new ProducerRecord<>(
            kafkaTopicName,
            genericRowPair.getLeft(),
            genericRowPair.getRight());
      } else {
        producerRecord = new ProducerRecord<>(
            kafkaTopicName,
            null,
            timestampGenerator.next(),
            genericRowPair.getLeft(),
            genericRowPair.getRight());
      }
      producer.send(producerRecord,
          new ErrorLoggingCallback(kafkaTopicName,
              genericRowPair.getLeft(),
              genericRowPair.getRight()));
      if (printRows) {
        System.err.println(producerRecord.key() + " --> (" + producerRecord.value() + ")");
      }
      if (maxInterval > 0) {
        try {
          Thread.sleep((long) (maxInterval * Math.random()));
        } catch (final InterruptedException e) {
          // Ignore the exception.
        }
      }
    }
    producer.flush();
    producer.close();
  }

  // For test purpose.
  protected Pair<String, GenericRow> generateOneGenericRow(
      final Generator generator,
      final AvroData avroData,
      final Schema avroSchema,
      final org.apache.kafka.connect.data.Schema ksqlSchema,
      final SessionManager sessionManager,
      final String key) {

    // generate avro record
    final Object generatedObject = generator.generate();
    if (!(generatedObject instanceof GenericRecord)) {
      throw new RuntimeException(String.format(
          "Expected Avro Random Generator to return instance of GenericRecord, found %s instead",
          generatedObject.getClass().getName()
      ));
    }
    final GenericRecord randomAvroMessage = (GenericRecord) generatedObject;
    SimpleDateFormat timeformatter = null;
    String sessionisationValue = null;
    for (final Schema.Field field : avroSchema.getFields()) {
      final boolean isSession = field.schema().getProp("session") != null;
      final boolean isSessionSiblingIntHash =
          field.schema().getProp("session-sibling-int-hash") != null;
      final String timeFormatFromLong = field.schema().getProp("format_as_time");

      if (isSession) {
        final String currentValue = (String) randomAvroMessage.get(field.name());
        final String newCurrentValue = handleSessionisationOfValue(sessionManager, currentValue);
        sessionisationValue = newCurrentValue;
        randomAvroMessage.put(field.name(), newCurrentValue);
      } else if (isSessionSiblingIntHash && sessionisationValue != null) {
        // super cheeky hack to link int-ids to session-values - if anything fails then we use
        // the 'avro-gen' randomised version
        randomAvroMessage.put(
            field.name(),
            handleSessionSiblingField(
                randomAvroMessage,
                sessionisationValue,
                field)
        );
      } else if (timeFormatFromLong != null) {
        final Date date = new Date(System.currentTimeMillis());
        if (timeFormatFromLong.equals("unix_long")) {
          randomAvroMessage.put(field.name(), date.getTime());
        } else {
          if (timeformatter == null) {
            timeformatter = new SimpleDateFormat(timeFormatFromLong);
          }
          randomAvroMessage.put(field.name(), timeformatter.format(date));
        }
      }
    }

    // convert to a ksql row so the serializers can deal with it
    final AvroDataTranslator dataTranslator = new AvroDataTranslator(ksqlSchema);
    final SchemaAndValue connectValue = avroData.toConnectData(avroSchema, randomAvroMessage);
    final GenericRow generatedRow = dataTranslator.toKsqlRow(connectValue.schema(), connectValue.value());

    final String keyString = avroData.toConnectData(
        randomAvroMessage.getSchema().getField(key).schema(),
        randomAvroMessage.get(key)).value().toString();

    return new Pair<>(keyString, generatedRow);
  }

  private static class ErrorLoggingCallback implements Callback {

    private final String topic;
    private final String key;
    private final GenericRow value;

    ErrorLoggingCallback(final String topic, final String key, final GenericRow value) {
      this.topic = topic;
      this.key = key;
      this.value = value;
    }

    @Override
    public void onCompletion(final RecordMetadata metadata, final Exception e) {
      final String keyString = Objects.toString(key);
      final String valueString = Objects.toString(value);

      if (e != null) {
        System.err.println("Error when sending message to topic: '" + topic + "', with key: '"
            + keyString + "', and value: '" + valueString + "'");
        e.printStackTrace(System.err);
      }
    }
  }

  private Object handleSessionSiblingField(
      final GenericRecord randomAvroMessage,
      final String sessionisationValue,
      final Schema.Field field
  ) {
    try {
      final Schema.Type type = field.schema().getType();
      if (type == Schema.Type.INT) {
        return mapSessionValueToSibling(sessionisationValue, field);
      } else {
        return randomAvroMessage.get(field.name());
      }
    } catch (final Exception err) {
      return randomAvroMessage.get(field.name());
    }
  }

  Map<String, Integer> sessionMap = new HashMap<>();
  Set<Integer> allocatedIds = new HashSet<>();

  private int mapSessionValueToSibling(String sessionisationValue, Schema.Field field) {

    if (!sessionMap.containsKey(sessionisationValue)) {

      LinkedHashMap properties =
          (LinkedHashMap) field.schema().getObjectProps().get("arg.properties");
      Integer max = (Integer) ((LinkedHashMap) properties.get("range")).get("max");

      int vvalue = Math.abs(sessionisationValue.hashCode() % max);

      int foundValue = -1;
      // used - search for another
      if (allocatedIds.contains(vvalue)) {
        for (int i = 0; i < max; i++) {
          if (!allocatedIds.contains(i)) {
            foundValue = i;
          }
        }
        if (foundValue == -1) {
          System.out.println(
              "Failed to allocate Id :"
                  + sessionisationValue
                  + ", reusing "
                  + vvalue
          );
          foundValue = vvalue;
        }
        vvalue = foundValue;
      }
      allocatedIds.add(vvalue);
      sessionMap.put(sessionisationValue, vvalue);
    }
    return sessionMap.get(sessionisationValue);

  }

  Set<String> allTokens = new HashSet<>();

  /**
   * If the sessionId is new Create a Session
   * If the sessionId is active - return the value
   * If the sessionId has expired - use a known token that is not expired
   *
   * @param sessionManager a SessionManager
   * @param currentValue current token
   * @return session token
   */
  private String handleSessionisationOfValue(SessionManager sessionManager, String currentValue) {

    // superset of all values
    allTokens.add(currentValue);

    /**
     * handle known sessions
     */
    if (sessionManager.isActive(currentValue)) {
      if (sessionManager.isExpired(currentValue)) {
        sessionManager.isActiveAndExpire(currentValue);
        return currentValue;
      } else {
        return currentValue;
      }
    }
    /**
     * If session count maxed out - reuse session tokens
     */
    if (sessionManager.getActiveSessionCount() > sessionManager.getMaxSessions()) {
      return sessionManager.getRandomActiveToken();
    }

    /**
     * Force expiring tokens to expire
     */
    String expired = sessionManager.getActiveSessionThatHasExpired();
    if (expired != null) {
      return expired;
    }

    /**
     * Use accummulated SessionTokens-tokens, or recycle old tokens or blow-up
     */
    String value = null;
    for (String token : allTokens) {
      if (value == null) {
        if (!sessionManager.isActive(token) && !sessionManager.isExpired(token)) {
          value = token;
        }
      }
    }

    if (value != null) {
      sessionManager.newSession(value);
    } else {
      value = sessionManager.recycleOldestExpired();
      if (value == null) {
        throw new RuntimeException(
            "Ran out of tokens to rejuice - increase session-duration (300s), reduce-number of "
                + "sessions(5), number of tokens in the avro template");
      }
      sessionManager.newSession(value);
      return value;
    }
    return currentValue;
  }

  protected abstract Serializer<GenericRow> getSerializer(
      Schema avroSchema,
      org.apache.kafka.connect.data.Schema kafkaSchema,
      String topicName
  );
}
