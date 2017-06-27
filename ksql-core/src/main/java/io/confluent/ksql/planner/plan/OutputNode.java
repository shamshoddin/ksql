/**
 * Copyright 2017 Confluent Inc.
 **/

package io.confluent.ksql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import org.apache.kafka.connect.data.Schema;

import javax.annotation.concurrent.Immutable;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Immutable
public abstract class OutputNode
    extends PlanNode {

  private final PlanNode source;
  private final Schema schema;
  private final Optional<Integer> limit;

  @JsonCreator
  protected OutputNode(@JsonProperty("id") final PlanNodeId id,
                       @JsonProperty("source") final PlanNode source,
                       @JsonProperty("schema") final Schema schema,
                       @JsonProperty("limit") final Optional<Integer> limit) {
    super(id);

    requireNonNull(source, "source is null");
    requireNonNull(schema, "schema is null");

    this.source = source;
    this.schema = schema;
    this.limit = limit;
  }

  @Override
  public Schema getSchema() {
    return this.schema;
  }

  @Override
  public List<PlanNode> getSources() {
    return ImmutableList.of(source);
  }

  public Optional<Integer> getLimit() {
    return limit;
  }

  @JsonProperty
  public PlanNode getSource() {
    return source;
  }

  @Override
  public <C, R> R accept(PlanVisitor<C, R> visitor, C context) {
    return visitor.visitOutput(this, context);
  }
}
