/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.ddl.commands;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import io.confluent.ksql.execution.ddl.commands.CreateStreamCommand;
import io.confluent.ksql.execution.ddl.commands.CreateTableCommand;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.streams.timestamp.TimestampExtractionPolicyFactory;
import io.confluent.ksql.execution.timestamp.TimestampColumn;
import io.confluent.ksql.logging.processing.NoopProcessingLogContext;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.properties.with.CreateSourceProperties;
import io.confluent.ksql.parser.tree.CreateSource;
import io.confluent.ksql.parser.tree.CreateStream;
import io.confluent.ksql.parser.tree.CreateTable;
import io.confluent.ksql.parser.tree.TableElements;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import io.confluent.ksql.schema.ksql.SystemColumns;
import io.confluent.ksql.schema.utils.FormatOptions;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.GenericKeySerDe;
import io.confluent.ksql.serde.GenericRowSerDe;
import io.confluent.ksql.serde.KeySerdeFactory;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.SerdeOptions;
import io.confluent.ksql.serde.ValueSerdeFactory;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.topic.TopicFactory;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class CreateSourceFactory {

  private final ServiceContext serviceContext;
  private final SerdeOptionsSupplier serdeOptionsSupplier;
  private final KeySerdeFactory keySerdeFactory;
  private final ValueSerdeFactory valueSerdeFactory;

  public CreateSourceFactory(final ServiceContext serviceContext) {
    this(
        serviceContext,
        SerdeOptions::buildForCreateStatement,
        new GenericKeySerDe(),
        new GenericRowSerDe()
    );
  }

  @VisibleForTesting
  CreateSourceFactory(
      final ServiceContext serviceContext,
      final SerdeOptionsSupplier serdeOptionsSupplier,
      final KeySerdeFactory keySerdeFactory,
      final ValueSerdeFactory valueSerdeFactory
  ) {
    this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    this.serdeOptionsSupplier =
        Objects.requireNonNull(serdeOptionsSupplier, "serdeOptionsSupplier");
    this.keySerdeFactory = Objects.requireNonNull(keySerdeFactory, "keySerdeFactory");
    this.valueSerdeFactory = Objects.requireNonNull(valueSerdeFactory, "valueSerdeFactory");
  }

  public CreateStreamCommand createStreamCommand(
      final CreateStream statement,
      final KsqlConfig ksqlConfig
  ) {
    final SourceName sourceName = statement.getName();
    final KsqlTopic topic = buildTopic(statement.getProperties(), serviceContext);
    final LogicalSchema schema = buildSchema(statement.getElements(), ksqlConfig);
    final Optional<ColumnName> keyFieldName = buildKeyFieldName(statement, schema);
    final Optional<TimestampColumn> timestampColumn = buildTimestampColumn(
        ksqlConfig,
        statement.getProperties(),
        schema
    );

    final Set<SerdeOption> serdeOptions = serdeOptionsSupplier.build(
        schema,
        topic.getValueFormat().getFormat(),
        statement.getProperties().getWrapSingleValues(),
        ksqlConfig
    );

    validateSerdesCanHandleSchemas(ksqlConfig, PhysicalSchema.from(schema, serdeOptions), topic);

    return new CreateStreamCommand(
        sourceName,
        schema,
        keyFieldName,
        timestampColumn,
        topic.getKafkaTopicName(),
        io.confluent.ksql.execution.plan.Formats
            .of(topic.getKeyFormat(), topic.getValueFormat(), serdeOptions),
        topic.getKeyFormat().getWindowInfo()
    );
  }

  public CreateTableCommand createTableCommand(
      final CreateTable statement,
      final KsqlConfig ksqlConfig
  ) {
    final SourceName sourceName = statement.getName();
    final KsqlTopic topic = buildTopic(statement.getProperties(), serviceContext);
    final LogicalSchema schema = buildSchema(statement.getElements(), ksqlConfig);
    final Optional<ColumnName> keyFieldName = buildKeyFieldName(statement, schema);
    final Optional<TimestampColumn> timestampColumn = buildTimestampColumn(
        ksqlConfig,
        statement.getProperties(),
        schema
    );
    final Set<SerdeOption> serdeOptions = serdeOptionsSupplier.build(
        schema,
        topic.getValueFormat().getFormat(),
        statement.getProperties().getWrapSingleValues(),
        ksqlConfig
    );

    validateSerdesCanHandleSchemas(ksqlConfig, PhysicalSchema.from(schema, serdeOptions), topic);

    return new CreateTableCommand(
        sourceName,
        schema,
        keyFieldName,
        timestampColumn,
        topic.getKafkaTopicName(),
        io.confluent.ksql.execution.plan.Formats
            .of(topic.getKeyFormat(), topic.getValueFormat(), serdeOptions),
        topic.getKeyFormat().getWindowInfo()
    );
  }

  private static Optional<ColumnName> buildKeyFieldName(
      final CreateSource statement,
      final LogicalSchema schema) {
    if (statement.getProperties().getKeyField().isPresent()) {
      final ColumnName column = statement.getProperties().getKeyField().get();
      schema.findValueColumn(column)
          .orElseThrow(() -> new KsqlException(
              "The KEY column set in the WITH clause does not exist in the schema: '"
                  + column.toString(FormatOptions.noEscape()) + "'"
          ));
      return Optional.of(column);
    } else {
      return Optional.empty();
    }
  }

  private static LogicalSchema buildSchema(
      final TableElements tableElements,
      final KsqlConfig ksqlConfig
  ) {
    if (Iterables.isEmpty(tableElements)) {
      throw new KsqlException("The statement does not define any columns.");
    }

    tableElements.forEach(e -> {
      if (SystemColumns.isSystemColumn(e.getName())) {
        throw new KsqlException("'" + e.getName().text() + "' is a reserved column name.");
      }

      if (!ksqlConfig.getBoolean(KsqlConfig.KSQL_ANY_KEY_NAME_ENABLED)) {
        final boolean isRowKey = e.getName().equals(SystemColumns.ROWKEY_NAME);
        if (e.getNamespace().isKey()) {
          if (!isRowKey) {
            throw new KsqlException("'" + e.getName().text() + "' is an invalid KEY column name. "
                + "KSQL currently only supports KEY columns named ROWKEY.");
          }
        } else if (isRowKey) {
          throw new KsqlException("'" + e.getName().text() + "' is a reserved column name. "
              + "It can only be used for KEY columns.");
        }
      }
    });

    return tableElements.toLogicalSchema(true);
  }

  private static KsqlTopic buildTopic(
      final CreateSourceProperties properties,
      final ServiceContext serviceContext
  ) {
    final String kafkaTopicName = properties.getKafkaTopic();
    if (!serviceContext.getTopicClient().isTopicExists(kafkaTopicName)) {
      throw new KsqlException("Kafka topic does not exist: " + kafkaTopicName);
    }

    return TopicFactory.create(properties);
  }

  private static Optional<TimestampColumn> buildTimestampColumn(
      final KsqlConfig ksqlConfig,
      final CreateSourceProperties properties,
      final LogicalSchema schema
  ) {
    final Optional<ColumnName> timestampName = properties.getTimestampColumnName();
    final Optional<TimestampColumn> timestampColumn = timestampName.map(
        n -> new TimestampColumn(n, properties.getTimestampFormat())
    );
    // create the final extraction policy to validate that the ref/format are OK
    TimestampExtractionPolicyFactory.validateTimestampColumn(ksqlConfig, schema, timestampColumn);
    return timestampColumn;
  }

  private void validateSerdesCanHandleSchemas(
      final KsqlConfig ksqlConfig,
      final PhysicalSchema physicalSchema,
      final KsqlTopic topic
  ) {
    keySerdeFactory.create(
        topic.getKeyFormat().getFormatInfo(),
        physicalSchema.keySchema(),
        ksqlConfig,
        serviceContext.getSchemaRegistryClientFactory(),
        "",
        NoopProcessingLogContext.INSTANCE
    ).close();

    valueSerdeFactory.create(
        topic.getValueFormat().getFormatInfo(),
        physicalSchema.valueSchema(),
        ksqlConfig,
        serviceContext.getSchemaRegistryClientFactory(),
        "",
        NoopProcessingLogContext.INSTANCE
    ).close();
  }

  @FunctionalInterface
  interface SerdeOptionsSupplier {

    Set<SerdeOption> build(
        LogicalSchema schema,
        Format valueFormat,
        Optional<Boolean> wrapSingleValues,
        KsqlConfig ksqlConfig
    );
  }
}
