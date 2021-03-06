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

package io.confluent.ksql.function.udaf;

import io.confluent.ksql.GenericRow;
import io.confluent.ksql.function.KsqlAggregateFunction;
import io.confluent.ksql.function.UdafAggregator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.streams.kstream.Merger;

public class KudafAggregator implements UdafAggregator {

  private Map<Integer, KsqlAggregateFunction> aggValToAggFunctionMap;
  private Map<Integer, Integer> aggValToValColumnMap;

  public KudafAggregator(
      final Map<Integer, KsqlAggregateFunction> aggValToAggFunctionMap,
      final Map<Integer, Integer> aggValToValColumnMap) {
    this.aggValToAggFunctionMap = aggValToAggFunctionMap;
    this.aggValToValColumnMap = aggValToValColumnMap;
  }

  @SuppressWarnings("unchecked")
  @Override
  public GenericRow apply(final String s, final GenericRow rowValue, final GenericRow aggRowValue) {
    // copy over group-by and aggregate parameter columns into the output row
    aggValToValColumnMap.forEach(
        (key, value) ->
            aggRowValue.getColumns().set(key, rowValue.getColumns().get(value)));

    // compute the aggregation and write it into the output row. Its assumed that
    // the columns written by this statement do not overlap with those written by
    // the above statement.
    aggValToAggFunctionMap.forEach((key, value) ->
        aggRowValue.getColumns().set(
            key,
            value.aggregate(
                rowValue.getColumns().get(value.getArgIndexInValue()),
                aggRowValue.getColumns().get(key))));

    return aggRowValue;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Merger<String, GenericRow> getMerger() {
    return (key, aggRowOne, aggRowTwo) -> {
      final List<Object> columns = Stream.generate(String::new).limit(aggRowOne.getColumns().size())
          .collect(Collectors.toList());
      final GenericRow mergedRow = new GenericRow(columns);

      aggValToValColumnMap.forEach((columnIndex, value) -> {
        if (aggRowOne.getColumns().get(value) == null) {
          mergedRow.getColumns().set(columnIndex, aggRowTwo.getColumns()
              .get(value));
        } else {
          mergedRow.getColumns().set(columnIndex, aggRowOne.getColumns()
              .get(value));
        }
      });

      aggValToAggFunctionMap.forEach((functionIndex, ksqlAggregateFunction) ->
          mergedRow.getColumns().set(functionIndex, ksqlAggregateFunction.getMerger()
          .apply(key,
              aggRowOne.getColumns().get(functionIndex),
              aggRowTwo.getColumns().get(functionIndex))));

      return mergedRow;
    };
  }

}
