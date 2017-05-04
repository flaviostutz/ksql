package io.confluent.kql.integtests.json;

import io.confluent.kql.KQLEngine;
import io.confluent.kql.metastore.KQLStream;
import io.confluent.kql.metastore.KQLTopic;
import io.confluent.kql.metastore.MetaStore;
import io.confluent.kql.metastore.MetaStoreImpl;
import io.confluent.kql.physical.GenericRow;
import io.confluent.kql.serde.json.KQLJsonPOJODeserializer;
import io.confluent.kql.serde.json.KQLJsonPOJOSerializer;
import io.confluent.kql.serde.json.KQLJsonTopicSerDe;
import io.confluent.kql.testutils.EmbeddedSingleNodeKafkaCluster;
import io.confluent.kql.util.KQLConfig;
import io.confluent.kql.util.PersistentQueryMetadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Created by hojjat on 5/3/17.
 */
public class JSONFormatTest {

  MetaStore metaStore;
  KQLEngine kqlEngine;
  Map<String, GenericRow> inputData;

  @ClassRule
  public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();

  private static final long RESULTS_POLL_MAX_TIME_MS = 30000;
  private static final String inputTopic = "orders_topic";
  private static final String inputStream = "ORDERS";

  @Before
  public void before() throws IOException {
    metaStore = new MetaStoreImpl();
    SchemaBuilder schemaBuilderOrders = SchemaBuilder.struct()
        .field("ORDERTIME", SchemaBuilder.INT64_SCHEMA)
        .field("ORDERID", SchemaBuilder.STRING_SCHEMA)
        .field("ITEMID", SchemaBuilder.STRING_SCHEMA)
        .field("ORDERUNITS", SchemaBuilder.FLOAT64_SCHEMA)
        .field("PRICEARRAY", SchemaBuilder.array(SchemaBuilder.FLOAT64_SCHEMA))
        .field("KEYVALUEMAP", SchemaBuilder.map(SchemaBuilder.STRING_SCHEMA, SchemaBuilder.FLOAT64_SCHEMA));

    KQLTopic
        kqlTopicOrders =
        new KQLTopic("ORDERS_TOPIC", "orders_topic", new KQLJsonTopicSerDe(null));

    KQLStream
        kqlStreamOrders = new KQLStream(inputStream, schemaBuilderOrders, schemaBuilderOrders.field("ORDERTIME"),
                                        kqlTopicOrders);

    metaStore.putTopic(kqlTopicOrders);
    metaStore.putSource(kqlStreamOrders);
    Map<String, Object> configMap = new HashMap<>();
    configMap.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    configMap.put("application.id", "KSQL");
    configMap.put("commit.interval.ms", 0);
    configMap.put("cache.max.bytes.buffering", 0);
    configMap.put("auto.offset.reset", "earliest");
    KQLConfig kqlConfig = new KQLConfig(configMap);
    kqlEngine = new KQLEngine(metaStore, kqlConfig);
    inputData = getInputData();
    produceInputData(inputData, kqlStreamOrders.getSchema());
  }


  @Test
  public void testSelectStar() throws Exception {
    final String streamName = "STARTSTREAM";
    final String queryString = String.format("CREATE STREAM %s AS SELECT * FROM %s;", streamName, inputStream);

    PersistentQueryMetadata queryMetadata =
        (PersistentQueryMetadata) kqlEngine.buildMultipleQueries(true, queryString).get(0);
    queryMetadata.getKafkaStreams().start();

    Schema resultSchema = metaStore.getSource(streamName).getSchema();
    Map<String, GenericRow> results = readResults(streamName, resultSchema, inputData.size());

    Assert.assertEquals(inputData.size(), results.size());
    Assert.assertTrue(assertExpectedResults(results, inputData));

    kqlEngine.terminateQuery(queryMetadata.getId(), true);
  }


  @Test
  public void testSelectProject() throws Exception {
    final String streamName = "STARTSTREAM";
    final String queryString =
        String.format("CREATE STREAM %s AS SELECT ITEMID, ORDERUNITS, PRICEARRAY FROM %s;", streamName, inputStream);

    PersistentQueryMetadata queryMetadata =
        (PersistentQueryMetadata) kqlEngine.buildMultipleQueries(true, queryString).get(0);
    queryMetadata.getKafkaStreams().start();

    Schema resultSchema = metaStore.getSource(streamName).getSchema();

    Map<String, GenericRow> expectedResults = new HashMap<>();
    expectedResults.put("1", new GenericRow(Arrays.asList("ITEM_1", 10.0, new
        Double[]{100.0,
                 110.99,
                 90.0 })));
    expectedResults.put("2", new GenericRow(Arrays.asList("ITEM_2", 20.0, new
        Double[]{10.0,
                 10.99,
                 9.0 })));

    expectedResults.put("3", new GenericRow(Arrays.asList("ITEM_3", 30.0, new
        Double[]{10.0,
                 10.99,
                 91.0 })));

    expectedResults.put("4", new GenericRow(Arrays.asList("ITEM_4", 40.0, new
        Double[]{10.0,
                 140.99,
                 94.0 })));

    expectedResults.put("5", new GenericRow(Arrays.asList("ITEM_5", 50.0, new
        Double[]{160.0,
                 160.99,
                 98.0 })));

    expectedResults.put("6", new GenericRow(Arrays.asList("ITEM_6", 60.0, new
        Double[]{1000.0,
                 1100.99,
                 900.0 })));

    expectedResults.put("7", new GenericRow(Arrays.asList("ITEM_7", 70.0, new
        Double[]{1100.0,
                 1110.99,
                 190.0 })));

    expectedResults.put("8", new GenericRow(Arrays.asList("ITEM_8", 80.0, new
        Double[]{1100.0,
                 1110.99,
                 970.0 })));

    Map<String, GenericRow> results = readResults(streamName, resultSchema, expectedResults.size());

    Assert.assertEquals(expectedResults.size(), results.size());
    Assert.assertTrue(assertExpectedResults(results, expectedResults));

    kqlEngine.terminateQuery(queryMetadata.getId(), true);
  }


  @Test
  public void testSelectFilter() throws Exception {
    final String streamName = "FILTERSTREAM";
    final String queryString = String.format(
        "CREATE STREAM %s AS SELECT * FROM %s WHERE ORDERUNITS > 20 AND ITEMID = 'ITEM_8';",
        streamName,
        inputStream
    );

    PersistentQueryMetadata queryMetadata =
        (PersistentQueryMetadata) kqlEngine.buildMultipleQueries(true, queryString).get(0);
    queryMetadata.getKafkaStreams().start();

    Schema resultSchema = metaStore.getSource(streamName).getSchema();

    Map<String, GenericRow> expectedResults = new HashMap<>();
    Map<String, Double> mapField = new HashMap<>();
    mapField.put("key1", 1.0);
    mapField.put("key2", 2.0);
    mapField.put("key3", 3.0);
    expectedResults.put("8", new GenericRow(Arrays.asList(8, "ORDER_6",
                                                         "ITEM_8", 80.0, new
                                                             Double[]{1100.0,
                                                                      1110.99,
                                                                      970.0 },
                                                         mapField)));

    Map<String, GenericRow> results = readResults(streamName, resultSchema, expectedResults.size());

    Assert.assertEquals(expectedResults.size(), results.size());
    Assert.assertTrue(assertExpectedResults(results, expectedResults));

    kqlEngine.terminateQuery(queryMetadata.getId(), true);
  }

  @Test
  public void testSelectExpression() throws Exception {
    final String streamName = "FILTERSTREAM";

    final String selectColumns =
        "ITEMID, ORDERUNITS*10, PRICEARRAY[0]+10, KEYVALUEMAP['key1']*KEYVALUEMAP['key2']+10, PRICEARRAY[1]>1000";
    final String whereClause = "ORDERUNITS > 20 AND ITEMID LIKE '%_8'";

    final String queryString = String.format(
        "CREATE STREAM %s AS SELECT %s FROM %s WHERE %s;",
        streamName,
        selectColumns,
        inputStream,
        whereClause
    );

    PersistentQueryMetadata queryMetadata =
        (PersistentQueryMetadata) kqlEngine.buildMultipleQueries(true, queryString).get(0);
    queryMetadata.getKafkaStreams().start();

    Schema resultSchema = metaStore.getSource(streamName).getSchema();

    Map<String, GenericRow> expectedResults = new HashMap<>();
    expectedResults.put("8", new GenericRow(Arrays.asList("ITEM_8", 800.0, 1110.0, 12.0, true)));

    Map<String, GenericRow> results = readResults(streamName, resultSchema, expectedResults.size());

    Assert.assertEquals(expectedResults.size(), results.size());
    Assert.assertTrue(assertExpectedResults(results, expectedResults));

    kqlEngine.terminateQuery(queryMetadata.getId(), true);
  }

  @Test
  public void testSelectUDFs() throws Exception {
    final String streamName = "UDFSTREAM";

    final String selectColumns =
        "ITEMID, ORDERUNITS*10, PRICEARRAY[0]+10, KEYVALUEMAP['key1']*KEYVALUEMAP['key2']+10, PRICEARRAY[1]>1000";
    final String whereClause = "ORDERUNITS > 20 AND ITEMID LIKE '%_8'";

    final String queryString = String.format(
        "CREATE STREAM %s AS SELECT %s FROM %s WHERE %s;",
        streamName,
        selectColumns,
        inputStream,
        whereClause
    );

    PersistentQueryMetadata queryMetadata =
        (PersistentQueryMetadata) kqlEngine.buildMultipleQueries(true, queryString).get(0);
    queryMetadata.getKafkaStreams().start();

    Schema resultSchema = metaStore.getSource(streamName).getSchema();

    Map<String, GenericRow> expectedResults = new HashMap<>();
    expectedResults.put("8", new GenericRow(Arrays.asList("ITEM_8", 800.0, 1110.0, 12.0, true)));

    Map<String, GenericRow> results = readResults(streamName, resultSchema, expectedResults.size());

    Assert.assertEquals(expectedResults.size(), results.size());
    Assert.assertTrue(assertExpectedResults(results, expectedResults));

    kqlEngine.terminateQuery(queryMetadata.getId(), true);
  }

  //*********************************************************//


  private void produceInputData(Map<String, GenericRow> recordsToPublish, Schema schema) {

    Properties producerConfig = new Properties();
    producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
    producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);

    KafkaProducer<String, GenericRow> producer =
        new KafkaProducer<>(producerConfig, new StringSerializer(), new KQLJsonPOJOSerializer(schema));

    for (String key: recordsToPublish.keySet()) {
      GenericRow row = recordsToPublish.get(key);
      ProducerRecord<String, GenericRow> producerRecord = new ProducerRecord<>(inputTopic, key, row);
      producer.send(producerRecord);
    }

    producer.close();
  }

  private Map<String, GenericRow> readResults(String resultTopic, Schema resultSchema, int expectedValues) {
    Properties consumerConfig = new Properties();
    consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
    consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG,
                       "filter-integration-test-standard-consumer");
    consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    KafkaConsumer<String, GenericRow> consumer = new KafkaConsumer<>(consumerConfig, new
        StringDeserializer(), new KQLJsonPOJODeserializer(resultSchema));

    consumer.subscribe(Collections.singletonList(resultTopic));
    long pollStart = System.currentTimeMillis();
    long pollEnd = pollStart + RESULTS_POLL_MAX_TIME_MS;
    Map<String, GenericRow> consumedValues = new HashMap<>();
    while (System.currentTimeMillis() < pollEnd) {
      ConsumerRecords<String, GenericRow> records = consumer.poll(Math.max(1, pollEnd - System.currentTimeMillis()));
      for (ConsumerRecord<String, GenericRow> record : records) {
        consumedValues.put(record.key(), record.value());
      }
      if (expectedValues > 0 && consumedValues.size() >= expectedValues) {
        break;
      }
    }
    consumer.close();
    return consumedValues;

  }

  private Map<String, GenericRow> getInputData() {

    Map<String, Double> mapField = new HashMap<>();
    mapField.put("key1", 1.0);
    mapField.put("key2", 2.0);
    mapField.put("key3", 3.0);

    Map<String, GenericRow> dataMap = new HashMap<>();
    dataMap.put("1", new GenericRow(Arrays.asList(1,
                                                  "ORDER_1",
                                                  "ITEM_1", 10.0, new
                                                      Double[]{100.0,
                                                               110.99,
                                                               90.0 },
                                                  mapField)));
    dataMap.put("2", new GenericRow(Arrays.asList(2, "ORDER_2",
                                                  "ITEM_2", 20.0, new
                                                      Double[]{10.0,
                                                               10.99,
                                                               9.0 },
                                                  mapField)));

    dataMap.put("3", new GenericRow(Arrays.asList(3, "ORDER_3",
                                                  "ITEM_3", 30.0, new
                                                      Double[]{10.0,
                                                               10.99,
                                                               91.0 },
                                                  mapField)));

    dataMap.put("4", new GenericRow(Arrays.asList(4, "ORDER_4",
                                                  "ITEM_4", 40.0, new
                                                      Double[]{10.0,
                                                               140.99,
                                                               94.0 },
                                                  mapField)));

    dataMap.put("5", new GenericRow(Arrays.asList(5, "ORDER_5",
                                                  "ITEM_5", 50.0, new
                                                      Double[]{160.0,
                                                               160.99,
                                                               98.0 },
                                                  mapField)));

    dataMap.put("6", new GenericRow(Arrays.asList(6, "ORDER_6",
                                                  "ITEM_6", 60.0, new
                                                      Double[]{1000.0,
                                                               1100.99,
                                                               900.0 },
                                                  mapField)));

    dataMap.put("7", new GenericRow(Arrays.asList(7, "ORDER_6",
                                                  "ITEM_7", 70.0, new
                                                      Double[]{1100.0,
                                                               1110.99,
                                                               190.0 },
                                                  mapField)));

    dataMap.put("8", new GenericRow(Arrays.asList(8, "ORDER_6",
                                                  "ITEM_8", 80.0, new
                                                      Double[]{1100.0,
                                                               1110.99,
                                                               970.0 },
                                                  mapField)));

    return dataMap;
  }

  private boolean assertExpectedResults(Map<String, GenericRow> actualResult,
                                        Map<String, GenericRow> expectedResult) {
    if (actualResult.size() != expectedResult.size()) {
      return false;
    }
    for (String k: expectedResult.keySet()) {
      if (!actualResult.containsKey(k)) {
        return false;
      }
      if (!expectedResult.get(k).hasTheSameContent(actualResult.get(k))) {
        return false;
      }
    }
    return true;
  }
}
