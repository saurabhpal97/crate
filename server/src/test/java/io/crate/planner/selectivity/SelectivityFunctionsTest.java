/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.planner.selectivity;


import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Test;

import io.crate.common.collections.Lists2;
import io.crate.data.Row1;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.ColumnIdent;
import io.crate.statistics.ColumnStats;
import io.crate.statistics.Stats;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.testing.SqlExpressions;
import io.crate.testing.T3;
import io.crate.types.DataTypes;

public class SelectivityFunctionsTest extends CrateDummyClusterServiceUnitTest {

    @Test
    public void test_eq_not_in_mcv_is_based_on_approx_distinct() {
        SqlExpressions expressions = new SqlExpressions(T3.sources(clusterService));
        Symbol query = expressions.asSymbol("x = 10");
        var statsByColumn = new HashMap<ColumnIdent, ColumnStats<?>>();
        var numbers = IntStream.range(1, 20_001)
            .boxed()
            .collect(Collectors.toList());
        var columnStats = ColumnStats.fromSortedValues(numbers, DataTypes.INTEGER, 0, 20_000L);
        statsByColumn.put(new ColumnIdent("x"), columnStats);
        Stats stats = new Stats(20_000, 16, statsByColumn);
        assertThat(SelectivityFunctions.estimateNumRows(stats, query, null)).isEqualTo(1L);
    }

    @Test
    public void test_eq_null_value_is_always_0() {
        SqlExpressions expressions = new SqlExpressions(T3.sources(clusterService));
        Symbol query = expressions.asSymbol("x = null");
        var numbers = IntStream.range(1, 50)
            .boxed()
            .collect(Collectors.toList());
        var columnStats = ColumnStats.fromSortedValues(numbers, DataTypes.INTEGER, 0, 20_000L);
        var statsByColumn = new HashMap<ColumnIdent, ColumnStats<?>>();
        statsByColumn.put(new ColumnIdent("x"), columnStats);
        Stats stats = new Stats(20_000, 16, statsByColumn);
        assertThat(SelectivityFunctions.estimateNumRows(stats, query, null)).isEqualTo(0L);
    }

    @Test
    public void test_column_eq_column_uses_approx_distinct_for_selectivity_approximation() {
        SqlExpressions expressions = new SqlExpressions(T3.sources(clusterService));
        Symbol query = expressions.asSymbol("x = y");
        var numbers = Lists2.concat(
            List.of(1, 1, 1, 1, 1, 1, 1, 5, 5, 5, 10, 10, 10, 10, 10, 10, 10, 10),
            IntStream.range(11, 15).boxed().collect(Collectors.toList())
        );
        var columnStats = ColumnStats.fromSortedValues(numbers, DataTypes.INTEGER, 0, numbers.size());
        var statsByColumn = Map.<ColumnIdent, ColumnStats<?>>of(new ColumnIdent("x"), columnStats);
        Stats stats = new Stats(numbers.size(), 16, statsByColumn);
        assertThat(SelectivityFunctions.estimateNumRows(stats, query, null)).isEqualTo(3L);
    }

    @Test
    public void test_eq_value_that_is_present_in_mcv_uses_mcv_frequency_as_selectivity() {
        SqlExpressions expressions = new SqlExpressions(T3.sources(clusterService));
        Symbol query = expressions.asSymbol("x = ?");
        var numbers = Lists2.concat(
            List.of(1, 1, 1, 1, 1, 1, 1, 5, 5, 5, 10, 10, 10, 10, 10, 10, 10, 10),
            IntStream.range(11, 15).boxed().collect(Collectors.toList())
        );
        var columnStats = ColumnStats.fromSortedValues(numbers, DataTypes.INTEGER, 0, numbers.size());
        double frequencyOf10 = columnStats.mostCommonValues().frequencies()[0];
        var statsByColumn = Map.<ColumnIdent, ColumnStats<?>>of(new ColumnIdent("x"), columnStats);
        Stats stats = new Stats(numbers.size(), 16, statsByColumn);
        assertThat(SelectivityFunctions.estimateNumRows(stats, query, new Row1(10)))
            .isEqualTo((long)(frequencyOf10 * numbers.size()));
    }

    @Test
    public void test_not_reverses_selectivity_of_inner_function() {
        SqlExpressions expressions = new SqlExpressions(T3.sources(clusterService));
        Symbol query = expressions.asSymbol("NOT (x = 10)");
        var numbers = IntStream.range(1, 20_001)
            .boxed()
            .collect(Collectors.toList());
        var columnStats = ColumnStats.fromSortedValues(numbers, DataTypes.INTEGER, 0, 20_000L);
        Stats stats = new Stats(20_000, 16, Map.of(new ColumnIdent("x"), columnStats));
        assertThat(SelectivityFunctions.estimateNumRows(stats, query, null)).isEqualTo(19999L);
    }

    @Test
    public void test_col_is_null_uses_null_fraction_as_selectivity() {
        SqlExpressions expressions = new SqlExpressions(T3.sources(clusterService));
        Symbol query = expressions.asSymbol("x is null");
        var columnStats = ColumnStats.fromSortedValues(List.of(1, 2), DataTypes.INTEGER, 2, 4);
        assertThat(columnStats.nullFraction()).isEqualTo(0.5);
        Stats stats = new Stats(100, 16, Map.of(new ColumnIdent("x"), columnStats));
        assertThat(SelectivityFunctions.estimateNumRows(stats, query, null)).isEqualTo(50L);
    }

    @Test
    public void test_eqjoin_uses_mcv_information() throws Exception {
        SqlExpressions expressions = new SqlExpressions(T3.sources(clusterService));
        Symbol query = expressions.asSymbol("x = y");
        int numTotalRows = 40;
        ArrayList<Integer> xValues = new ArrayList<>();
        for (int i = 0; i < numTotalRows; i++) {
            if (i < 30) {
                xValues.add(1);
            } else {
                xValues.add(2);
            }
        }
        ArrayList<Integer> yValues = new ArrayList<>();
        for (int i = 0; i < numTotalRows; i++) {
            if (i < 30) {
                yValues.add(10);
            } else {
                yValues.add(2);
            }
        }
        var xStats = ColumnStats.fromSortedValues(xValues, DataTypes.INTEGER, 0, numTotalRows);
        assertThat(xStats.mostCommonValues().isEmpty())
            .as("Test case depends on most common values")
            .isFalse();
        var yStats = ColumnStats.fromSortedValues(yValues, DataTypes.INTEGER, 0, numTotalRows);
        assertThat(yStats.mostCommonValues().isEmpty())
            .as("Test case depends on most common values")
            .isFalse();
        Map<ColumnIdent, ColumnStats<?>> columnStats = Map.of(
            new ColumnIdent("x"),
            xStats,
            new ColumnIdent("y"),
            yStats
        );
        Stats stats = new Stats(numTotalRows, 32, columnStats);
        long numRows = SelectivityFunctions.estimateNumRows(stats, query, null);
        assertThat(numRows).isEqualTo(2L);
    }
}
