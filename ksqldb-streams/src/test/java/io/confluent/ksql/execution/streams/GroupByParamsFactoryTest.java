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

package io.confluent.ksql.execution.streams;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.NullPointerTester;
import com.google.common.testing.NullPointerTester.Visibility;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.codegen.ExpressionMetadata;
import io.confluent.ksql.execution.expression.tree.DereferenceExpression;
import io.confluent.ksql.execution.expression.tree.LongLiteral;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.util.StructKeyUtil;
import io.confluent.ksql.logging.processing.ProcessingLogConfig;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.logging.processing.RecordProcessingError;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlStruct;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.SchemaUtil;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(Parameterized.class)
public class GroupByParamsFactoryTest {

  private static final ColumnName COL3 = ColumnName.of("COL3");
  private static final SqlStruct COL3_TYPE = SqlTypes.struct()
      .field("someField", SqlTypes.BIGINT)
      .build();

  private static final LogicalSchema SOURCE_SCHEMA = LogicalSchema.builder()
      .valueColumn(ColumnName.of("v0"), SqlTypes.DOUBLE)
      .valueColumn(ColumnName.of("KSQL_COL_0"), SqlTypes.DOUBLE)
      .valueColumn(COL3, COL3_TYPE)
      .build();

  @Rule
  public final MockitoRule initMockito = MockitoJUnit.rule();

  @Mock
  private ExpressionMetadata groupBy0;
  @Mock
  private ExpressionMetadata groupBy1;
  @Mock
  private GenericRow value;
  @Mock
  private ProcessingLogger logger;
  @Mock
  private KsqlConfig ksqlConfig;
  @Captor
  private ArgumentCaptor<Function<ProcessingLogConfig, SchemaAndValue>> msgCaptor;

  private final boolean anyKeyName;
  private GroupByParams singleParams;
  private GroupByParams multiParams;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return ImmutableList.of(
        new Object[]{"old skool", false},
        new Object[]{"new skool", true}
    );
  }

  public GroupByParamsFactoryTest(final String name, final boolean anyKeyName) {
    this.anyKeyName = anyKeyName;
  }

  @Before
  public void setUp() {
    when(ksqlConfig.getBoolean(KsqlConfig.KSQL_ANY_KEY_NAME_ENABLED)).thenReturn(anyKeyName);

    when(groupBy0.getExpression()).thenReturn(new LongLiteral(0));
    when(groupBy0.getExpressionType()).thenReturn(SqlTypes.INTEGER);

    singleParams = GroupByParamsFactory
        .build(SOURCE_SCHEMA, ImmutableList.of(groupBy0), Optional.empty(), logger, ksqlConfig);

    multiParams = GroupByParamsFactory
        .build(SOURCE_SCHEMA, ImmutableList.of(groupBy0, groupBy1), Optional.empty(), logger, ksqlConfig);

    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(0);
    when(groupBy1.evaluate(any(), any(), any(), any())).thenReturn(0L);
  }

  @SuppressWarnings("UnstableApiUsage")
  @Test
  public void shouldThrowOnNullParam() {
    new NullPointerTester()
        .setDefault(List.class, ImmutableList.of(groupBy0))
        .setDefault(LogicalSchema.class, SOURCE_SCHEMA)
        .setDefault(SqlType.class, SqlTypes.BIGINT)
        .setDefault(KsqlConfig.class, ksqlConfig)
        .testStaticMethods(GroupByParamsFactory.class, Visibility.PACKAGE);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldThrowOnEmptyParam() {
    GroupByParamsFactory
        .build(SOURCE_SCHEMA, Collections.emptyList(), Optional.empty(), logger, ksqlConfig);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldInvokeSingleEvaluatorsWithCorrectParams() {
    // When:
    singleParams.getMapper().apply(value);

    // Then:
    final ArgumentCaptor<Supplier<String>> errorMsgCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(groupBy0).evaluate(eq(value), any(), eq(logger), errorMsgCaptor.capture());

    assertThat(errorMsgCaptor.getValue().get(), is(
        "Error calculating group-by column with index 0. The source row will be excluded from the table."
    ));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldInvokeMultipleEvaluatorsWithCorrectParams() {
    // When:
    multiParams.getMapper().apply(value);

    // Then:
    final ArgumentCaptor<Supplier<String>> errorMsgCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(groupBy0).evaluate(eq(value), any(), eq(logger), errorMsgCaptor.capture());
    verify(groupBy1).evaluate(eq(value), any(), eq(logger), errorMsgCaptor.capture());

    final List<String> errorMsgs = errorMsgCaptor.getAllValues().stream()
        .map(Supplier::get)
        .collect(Collectors.toList());

    assertThat(errorMsgs, contains(
        "Error calculating group-by column with index 0. The source row will be excluded from the table.",
        "Error calculating group-by column with index 1. The source row will be excluded from the table."
    ));
  }

  @Test
  public void shouldGenerateSingleExpressionGroupByKey() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(10);

    // When:
    final Struct result = singleParams.getMapper().apply(value);

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? ColumnName.of("KSQL_COL_1")
        : SchemaUtil.ROWKEY_NAME;

    assertThat(result, is(structKey(expectedKeyColName, 10)));
  }

  @Test
  public void shouldGenerateSingleExpressionWithAliasGroupByKey() {
    // Given:
    final ColumnName keyAlias = ColumnName.of("NEW_KEY");
    givenAliasOf(keyAlias);

    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(10);

    // When:
    final Struct result = singleParams.getMapper().apply(value);

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? keyAlias
        : SchemaUtil.ROWKEY_NAME;

    assertThat(result, is(structKey(expectedKeyColName, 10)));
  }

  @Test
  public void shouldGenerateMultiExpressionGroupByKey() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(99);
    when(groupBy1.evaluate(any(), any(), any(), any())).thenReturn(-100L);

    // When:
    final Struct result = multiParams.getMapper().apply(value);

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? ColumnName.of("KSQL_COL_1")
        : SchemaUtil.ROWKEY_NAME;

    assertThat(result, is(structKey(expectedKeyColName, "99|+|-100")));
  }

  @Test
  public void shouldGenerateMultiExpressionWithAliasGroupByKey() {
    // Given:
    final ColumnName keyAlias = ColumnName.of("NEW_KEY");
    givenAliasOf(keyAlias);

    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(99);
    when(groupBy1.evaluate(any(), any(), any(), any())).thenReturn(-100L);

    // When:
    final Struct result = multiParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(structKey(keyAlias, "99|+|-100")));
  }

  @Test
  public void shouldReturnNullIfSingleExpressionResolvesToNull() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    final Struct result = singleParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(nullValue()));
  }

  @Test
  public void shouldLogProcessingErrorIfSingleExpressionResolvesToNull() {
    // Given
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    singleParams.getMapper().apply(value);

    // Then:
    verify(logger).error(
        RecordProcessingError.recordProcessingError(
            "Group-by column with index 0 resolved to null. "
                + "The source row will be excluded from the table.",
            value
        )
    );
  }

  @Test
  public void shouldReturnNullIfAnyMultiExpressionResolvesToNull() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    final Struct result = multiParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(nullValue()));
  }

  @Test
  public void shouldLogProcessingErrorIfAnyMultiExpressionResolvesToNull() {
    // Given
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    multiParams.getMapper().apply(value);

    // Then:
    verify(logger).error(
        RecordProcessingError.recordProcessingError(
            "Group-by column with index 0 resolved to null. "
                + "The source row will be excluded from the table.",
            value
        )
    );
  }

  @Test
  public void shouldUseNullInGroupByIfOneExpressionFailsOrReturnsNullInMulti() {
    // Given:
    when(groupBy0.evaluate(any(), any(), any(), any())).thenReturn(null);

    // When:
    final Struct result = multiParams.getMapper().apply(value);

    // Then:
    assertThat(result, is(nullValue()));
  }

  @Test
  public void shouldSetKeyNameFromSingleGroupByColumnName() {
    // When:
    when(groupBy0.getExpression())
        .thenReturn(new UnqualifiedColumnReferenceExp(ColumnName.of("Bob")));

    // When:
    final LogicalSchema schema = GroupByParamsFactory
        .buildSchema(SOURCE_SCHEMA, ImmutableList.of(groupBy0), Optional.empty(), ksqlConfig);

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? ColumnName.of("Bob")
        : SchemaUtil.ROWKEY_NAME;

    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(expectedKeyColName, SqlTypes.INTEGER)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldSetKeyNameFromSingleGroupByFieldName() {
    // When:
    when(groupBy0.getExpression()).thenReturn(new DereferenceExpression(
        Optional.empty(),
        new UnqualifiedColumnReferenceExp(COL3),
        "someField"
    ));

    // When:
    final LogicalSchema schema = GroupByParamsFactory
        .buildSchema(SOURCE_SCHEMA, ImmutableList.of(groupBy0), Optional.empty(), ksqlConfig);

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? ColumnName.of("someField")
        : SchemaUtil.ROWKEY_NAME;

    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(expectedKeyColName, SqlTypes.INTEGER)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldSetKeyNameFromSingleAliasedGroupBy() {
    // When:
    final ColumnName keyAlias = ColumnName.of("NEW_KEY");

    when(groupBy0.getExpression())
        .thenReturn(new LongLiteral(1));

    // When:
    final LogicalSchema schema = GroupByParamsFactory.buildSchema(
        SOURCE_SCHEMA,
        ImmutableList.of(groupBy0),
        Optional.of(keyAlias),
        ksqlConfig
    );

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? keyAlias
        : SchemaUtil.ROWKEY_NAME;

    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(expectedKeyColName, SqlTypes.INTEGER)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldGenerateKeyNameFromSingleGroupByOtherExpressionType() {
    // When:
    when(groupBy0.getExpression())
        .thenReturn(new LongLiteral(1));

    // When:
    final LogicalSchema schema = GroupByParamsFactory
        .buildSchema(SOURCE_SCHEMA, ImmutableList.of(groupBy0), Optional.empty(), ksqlConfig);

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? ColumnName.of("KSQL_COL_1")
        : SchemaUtil.ROWKEY_NAME;

    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(expectedKeyColName, SqlTypes.INTEGER)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldGenerateKeyNameForMultiGroupBys() {
    // When:
    final LogicalSchema schema = GroupByParamsFactory.buildSchema(
        SOURCE_SCHEMA,
        ImmutableList.of(groupBy0, groupBy1),
        Optional.empty(),
        ksqlConfig
    );

    // Then:
    final ColumnName expectedKeyColName = anyKeyName
        ? ColumnName.of("KSQL_COL_1")
        : SchemaUtil.ROWKEY_NAME;

    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(expectedKeyColName, SqlTypes.STRING)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  @Test
  public void shouldGenerateKeyNameForAliasedMultiGroupBys() {
    // When:
    final ColumnName keyAlias = ColumnName.of("NEW_KEY");

    final LogicalSchema schema = GroupByParamsFactory.buildSchema(
        SOURCE_SCHEMA,
        ImmutableList.of(groupBy0, groupBy1),
        Optional.of(keyAlias),
        ksqlConfig
    );

    // Then:
    assertThat(schema, is(LogicalSchema.builder()
        .keyColumn(keyAlias, SqlTypes.STRING)
        .valueColumns(SOURCE_SCHEMA.value())
        .build()));
  }

  private void givenAliasOf(final ColumnName keyAlias) {
    singleParams = GroupByParamsFactory
        .build(SOURCE_SCHEMA, ImmutableList.of(groupBy0), Optional.of(keyAlias), logger, ksqlConfig);

    multiParams = GroupByParamsFactory
        .build(SOURCE_SCHEMA, ImmutableList.of(groupBy0, groupBy1), Optional.of(keyAlias), logger, ksqlConfig);
  }

  private static Struct structKey(final ColumnName keyColName, final String keyValue) {
    return StructKeyUtil
        .keyBuilder(keyColName, SqlTypes.STRING)
        .build(keyValue);
  }

  private static Struct structKey(final ColumnName keyColName, final int keyValue) {
    return StructKeyUtil
        .keyBuilder(keyColName, SqlTypes.INTEGER)
        .build(keyValue);
  }
}
