/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.test;

import org.apache.calcite.adapter.enumerable.EnumerableMergeJoin;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.SemiJoin;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.logical.LogicalUnion;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.metadata.CachingRelMetadataProvider;
import org.apache.calcite.rel.metadata.ChainedRelMetadataProvider;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.Metadata;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.hamcrest.CoreMatchers;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.junit.Ignore;
import org.junit.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Unit test for {@link DefaultRelMetadataProvider}. See
 * {@link SqlToRelTestBase} class comments for details on the schema used. Note
 * that no optimizer rules are fired on the translation of the SQL into
 * relational algebra (e.g. join conditions in the WHERE clause will look like
 * filters), so it's necessary to phrase the SQL carefully.
 */
public class RelMetadataTest extends SqlToRelTestBase {
  //~ Static fields/initializers ---------------------------------------------

  private static final double EPSILON = 1.0e-5;

  private static final double DEFAULT_EQUAL_SELECTIVITY = 0.15;

  private static final double DEFAULT_EQUAL_SELECTIVITY_SQUARED =
      DEFAULT_EQUAL_SELECTIVITY * DEFAULT_EQUAL_SELECTIVITY;

  private static final double DEFAULT_COMP_SELECTIVITY = 0.5;

  private static final double DEFAULT_NOTNULL_SELECTIVITY = 0.9;

  private static final double DEFAULT_SELECTIVITY = 0.25;

  private static final double EMP_SIZE = 14d;

  private static final double DEPT_SIZE = 4d;

  //~ Methods ----------------------------------------------------------------

  private static Matcher<? super Number> nearTo(Number v, Number epsilon) {
    return equalTo(v); // TODO: use epsilon
  }

  // ----------------------------------------------------------------------
  // Tests for getPercentageOriginalRows
  // ----------------------------------------------------------------------

  private RelNode convertSql(String sql) {
    final RelRoot root = tester.convertSqlToRel(sql);
    DefaultRelMetadataProvider provider = new DefaultRelMetadataProvider();
    root.rel.getCluster().setMetadataProvider(provider);
    return root.rel;
  }

  private void checkPercentageOriginalRows(String sql, double expected) {
    checkPercentageOriginalRows(sql, expected, EPSILON);
  }

  private void checkPercentageOriginalRows(
      String sql,
      double expected,
      double epsilon) {
    RelNode rel = convertSql(sql);
    Double result = RelMetadataQuery.getPercentageOriginalRows(rel);
    assertTrue(result != null);
    assertEquals(expected, result, epsilon);
  }

  @Test public void testPercentageOriginalRowsTableOnly() {
    checkPercentageOriginalRows(
        "select * from dept",
        1.0);
  }

  @Test public void testPercentageOriginalRowsAgg() {
    checkPercentageOriginalRows(
        "select deptno from dept group by deptno",
        1.0);
  }

  @Ignore
  @Test public void testPercentageOriginalRowsOneFilter() {
    checkPercentageOriginalRows(
        "select * from dept where deptno = 20",
        DEFAULT_EQUAL_SELECTIVITY);
  }

  @Ignore
  @Test public void testPercentageOriginalRowsTwoFilters() {
    checkPercentageOriginalRows("select * from (\n"
        + "  select * from dept where name='X')\n"
        + "where deptno = 20",
        DEFAULT_EQUAL_SELECTIVITY_SQUARED);
  }

  @Ignore
  @Test public void testPercentageOriginalRowsRedundantFilter() {
    checkPercentageOriginalRows("select * from (\n"
        + "  select * from dept where deptno=20)\n"
        + "where deptno = 20",
        DEFAULT_EQUAL_SELECTIVITY);
  }

  @Test public void testPercentageOriginalRowsJoin() {
    checkPercentageOriginalRows(
        "select * from emp inner join dept on emp.deptno=dept.deptno",
        1.0);
  }

  @Ignore
  @Test public void testPercentageOriginalRowsJoinTwoFilters() {
    checkPercentageOriginalRows("select * from (\n"
        + "  select * from emp where deptno=10) e\n"
        + "inner join (select * from dept where deptno=10) d\n"
        + "on e.deptno=d.deptno",
        DEFAULT_EQUAL_SELECTIVITY_SQUARED);
  }

  @Test public void testPercentageOriginalRowsUnionNoFilter() {
    checkPercentageOriginalRows(
        "select name from dept union all select ename from emp",
        1.0);
  }

  @Ignore
  @Test public void testPercentageOriginalRowsUnionLittleFilter() {
    checkPercentageOriginalRows(
        "select name from dept where deptno=20"
            + " union all select ename from emp",
        ((DEPT_SIZE * DEFAULT_EQUAL_SELECTIVITY) + EMP_SIZE)
            / (DEPT_SIZE + EMP_SIZE));
  }

  @Ignore
  @Test public void testPercentageOriginalRowsUnionBigFilter() {
    checkPercentageOriginalRows(
        "select name from dept"
            + " union all select ename from emp where deptno=20",
        ((EMP_SIZE * DEFAULT_EQUAL_SELECTIVITY) + DEPT_SIZE)
            / (DEPT_SIZE + EMP_SIZE));
  }

  // ----------------------------------------------------------------------
  // Tests for getColumnOrigins
  // ----------------------------------------------------------------------

  private Set<RelColumnOrigin> checkColumnOrigin(String sql) {
    RelNode rel = convertSql(sql);
    return RelMetadataQuery.getColumnOrigins(rel, 0);
  }

  private void checkNoColumnOrigin(String sql) {
    Set<RelColumnOrigin> result = checkColumnOrigin(sql);
    assertTrue(result != null);
    assertTrue(result.isEmpty());
  }

  public static void checkColumnOrigin(
      RelColumnOrigin rco,
      String expectedTableName,
      String expectedColumnName,
      boolean expectedDerived) {
    RelOptTable actualTable = rco.getOriginTable();
    List<String> actualTableName = actualTable.getQualifiedName();
    assertEquals(
        Iterables.getLast(actualTableName),
        expectedTableName);
    assertEquals(
        actualTable.getRowType()
            .getFieldList()
            .get(rco.getOriginColumnOrdinal())
            .getName(), expectedColumnName);
    assertEquals(
        rco.isDerived(), expectedDerived);
  }

  private void checkSingleColumnOrigin(
      String sql,
      String expectedTableName,
      String expectedColumnName,
      boolean expectedDerived) {
    Set<RelColumnOrigin> result = checkColumnOrigin(sql);
    assertTrue(result != null);
    assertEquals(
        1,
        result.size());
    RelColumnOrigin rco = result.iterator().next();
    checkColumnOrigin(
        rco, expectedTableName, expectedColumnName, expectedDerived);
  }

  // WARNING:  this requires the two table names to be different
  private void checkTwoColumnOrigin(
      String sql,
      String expectedTableName1,
      String expectedColumnName1,
      String expectedTableName2,
      String expectedColumnName2,
      boolean expectedDerived) {
    Set<RelColumnOrigin> result = checkColumnOrigin(sql);
    assertTrue(result != null);
    assertEquals(
        2,
        result.size());
    for (RelColumnOrigin rco : result) {
      RelOptTable actualTable = rco.getOriginTable();
      List<String> actualTableName = actualTable.getQualifiedName();
      String actualUnqualifiedName = Iterables.getLast(actualTableName);
      if (actualUnqualifiedName.equals(expectedTableName1)) {
        checkColumnOrigin(
            rco,
            expectedTableName1,
            expectedColumnName1,
            expectedDerived);
      } else {
        checkColumnOrigin(
            rco,
            expectedTableName2,
            expectedColumnName2,
            expectedDerived);
      }
    }
  }

  @Test public void testColumnOriginsTableOnly() {
    checkSingleColumnOrigin(
        "select name as dname from dept",
        "DEPT",
        "NAME",
        false);
  }

  @Test public void testColumnOriginsExpression() {
    checkSingleColumnOrigin(
        "select upper(name) as dname from dept",
        "DEPT",
        "NAME",
        true);
  }

  @Test public void testColumnOriginsDyadicExpression() {
    checkTwoColumnOrigin(
        "select name||ename from dept,emp",
        "DEPT",
        "NAME",
        "EMP",
        "ENAME",
        true);
  }

  @Test public void testColumnOriginsConstant() {
    checkNoColumnOrigin(
        "select 'Minstrelsy' as dname from dept");
  }

  @Test public void testColumnOriginsFilter() {
    checkSingleColumnOrigin(
        "select name as dname from dept where deptno=10",
        "DEPT",
        "NAME",
        false);
  }

  @Test public void testColumnOriginsJoinLeft() {
    checkSingleColumnOrigin(
        "select ename from emp,dept",
        "EMP",
        "ENAME",
        false);
  }

  @Test public void testColumnOriginsJoinRight() {
    checkSingleColumnOrigin(
        "select name as dname from emp,dept",
        "DEPT",
        "NAME",
        false);
  }

  @Test public void testColumnOriginsJoinOuter() {
    checkSingleColumnOrigin(
        "select name as dname from emp left outer join dept"
            + " on emp.deptno = dept.deptno",
        "DEPT",
        "NAME",
        true);
  }

  @Test public void testColumnOriginsJoinFullOuter() {
    checkSingleColumnOrigin(
        "select name as dname from emp full outer join dept"
            + " on emp.deptno = dept.deptno",
        "DEPT",
        "NAME",
        true);
  }

  @Test public void testColumnOriginsAggKey() {
    checkSingleColumnOrigin(
        "select name,count(deptno) from dept group by name",
        "DEPT",
        "NAME",
        false);
  }

  @Test public void testColumnOriginsAggReduced() {
    checkNoColumnOrigin(
        "select count(deptno),name from dept group by name");
  }

  @Test public void testColumnOriginsAggCountNullable() {
    checkSingleColumnOrigin(
        "select count(mgr),ename from emp group by ename",
        "EMP",
        "MGR",
        true);
  }

  @Test public void testColumnOriginsAggCountStar() {
    checkNoColumnOrigin(
        "select count(*),name from dept group by name");
  }

  @Test public void testColumnOriginsValues() {
    checkNoColumnOrigin(
        "values(1,2,3)");
  }

  @Test public void testColumnOriginsUnion() {
    checkTwoColumnOrigin(
        "select name from dept union all select ename from emp",
        "DEPT",
        "NAME",
        "EMP",
        "ENAME",
        false);
  }

  @Test public void testColumnOriginsSelfUnion() {
    checkSingleColumnOrigin(
        "select ename from emp union all select ename from emp",
        "EMP",
        "ENAME",
        false);
  }

  private void checkRowCount(
      String sql,
      double expected) {
    RelNode rel = convertSql(sql);
    Double result = RelMetadataQuery.getRowCount(rel);
    assertThat(result, notNullValue());
    assertEquals(expected, result, 0d);
  }

  private void checkMaxRowCount(
      String sql,
      double expected) {
    RelNode rel = convertSql(sql);
    Double result = RelMetadataQuery.getMaxRowCount(rel);
    assertThat(result, notNullValue());
    assertEquals(expected, result, 0d);
  }

  @Test public void testRowCountEmp() {
    final String sql = "select * from emp";
    checkRowCount(sql, EMP_SIZE);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountDept() {
    final String sql = "select * from dept";
    checkRowCount(sql, DEPT_SIZE);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountValues() {
    final String sql = "select * from (values (1), (2)) as t(c)";
    checkRowCount(sql, 2);
    checkMaxRowCount(sql, 2);
  }

  @Test public void testRowCountCartesian() {
    final String sql = "select * from emp,dept";
    checkRowCount(sql, EMP_SIZE * DEPT_SIZE);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountJoin() {
    final String sql = "select * from emp\n"
        + "inner join dept on emp.deptno = dept.deptno";
    checkRowCount(sql, EMP_SIZE * DEPT_SIZE * DEFAULT_EQUAL_SELECTIVITY);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountJoinFinite() {
    final String sql = "select * from (select * from emp limit 14) as emp\n"
        + "inner join (select * from dept limit 4) as dept\n"
        + "on emp.deptno = dept.deptno";
    checkRowCount(sql, EMP_SIZE * DEPT_SIZE * DEFAULT_EQUAL_SELECTIVITY);
    checkMaxRowCount(sql, 56D); // 4 * 14
  }

  @Test public void testRowCountJoinEmptyFinite() {
    final String sql = "select * from (select * from emp limit 0) as emp\n"
        + "inner join (select * from dept limit 4) as dept\n"
        + "on emp.deptno = dept.deptno";
    checkRowCount(sql, 1D); // 0, rounded up to row count's minimum 1
    checkMaxRowCount(sql, 0D); // 0 * 4
  }

  @Test public void testRowCountLeftJoinEmptyFinite() {
    final String sql = "select * from (select * from emp limit 0) as emp\n"
        + "left join (select * from dept limit 4) as dept\n"
        + "on emp.deptno = dept.deptno";
    checkRowCount(sql, 1D); // 0, rounded up to row count's minimum 1
    checkMaxRowCount(sql, 0D); // 0 * 4
  }

  @Test public void testRowCountRightJoinEmptyFinite() {
    final String sql = "select * from (select * from emp limit 0) as emp\n"
        + "right join (select * from dept limit 4) as dept\n"
        + "on emp.deptno = dept.deptno";
    checkRowCount(sql, 1D); // 0, rounded up to row count's minimum 1
    checkMaxRowCount(sql, 4D); // 1 * 4
  }

  @Test public void testRowCountJoinFiniteEmpty() {
    final String sql = "select * from (select * from emp limit 7) as emp\n"
        + "inner join (select * from dept limit 0) as dept\n"
        + "on emp.deptno = dept.deptno";
    checkRowCount(sql, 1D); // 0, rounded up to row count's minimum 1
    checkMaxRowCount(sql, 0D); // 7 * 0
  }

  @Test public void testRowCountJoinEmptyEmpty() {
    final String sql = "select * from (select * from emp limit 0) as emp\n"
        + "inner join (select * from dept limit 0) as dept\n"
        + "on emp.deptno = dept.deptno";
    checkRowCount(sql, 1D); // 0, rounded up to row count's minimum 1
    checkMaxRowCount(sql, 0D); // 0 * 0
  }

  @Test public void testRowCountUnion() {
    final String sql = "select ename from emp\n"
        + "union all\n"
        + "select name from dept";
    checkRowCount(sql, EMP_SIZE + DEPT_SIZE);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountUnionOnFinite() {
    final String sql = "select ename from (select * from emp limit 100)\n"
        + "union all\n"
        + "select name from (select * from dept limit 40)";
    checkRowCount(sql, EMP_SIZE + DEPT_SIZE);
    checkMaxRowCount(sql, 140D);
  }

  @Test public void testRowCountIntersectOnFinite() {
    final String sql = "select ename from (select * from emp limit 100)\n"
        + "intersect\n"
        + "select name from (select * from dept limit 40)";
    checkRowCount(sql, Math.min(EMP_SIZE, DEPT_SIZE));
    checkMaxRowCount(sql, 40D);
  }

  @Test public void testRowCountMinusOnFinite() {
    final String sql = "select ename from (select * from emp limit 100)\n"
        + "except\n"
        + "select name from (select * from dept limit 40)";
    checkRowCount(sql, 4D);
    checkMaxRowCount(sql, 100D);
  }

  @Test public void testRowCountFilter() {
    final String sql = "select * from emp where ename='Mathilda'";
    checkRowCount(sql, EMP_SIZE * DEFAULT_EQUAL_SELECTIVITY);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountFilterOnFinite() {
    final String sql = "select * from (select * from emp limit 10)\n"
        + "where ename='Mathilda'";
    checkRowCount(sql, 10D * DEFAULT_EQUAL_SELECTIVITY);
    checkMaxRowCount(sql, 10D);
  }

  @Test public void testRowCountSort() {
    final String sql = "select * from emp order by ename";
    checkRowCount(sql, EMP_SIZE);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountSortHighLimit() {
    final String sql = "select * from emp order by ename limit 123456";
    checkRowCount(sql, EMP_SIZE);
    checkMaxRowCount(sql, 123456D);
  }

  @Test public void testRowCountSortHighOffset() {
    final String sql = "select * from emp order by ename offset 123456";
    checkRowCount(sql, 1D);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountSortHighOffsetLimit() {
    final String sql = "select * from emp order by ename limit 5 offset 123456";
    checkRowCount(sql, 1D);
    checkMaxRowCount(sql, 5D);
  }

  @Test public void testRowCountSortLimit() {
    final String sql = "select * from emp order by ename limit 10";
    checkRowCount(sql, 10d);
    checkMaxRowCount(sql, 10d);
  }

  @Test public void testRowCountSortLimit0() {
    final String sql = "select * from emp order by ename limit 10";
    checkRowCount(sql, 10d);
    checkMaxRowCount(sql, 10d);
  }

  @Test public void testRowCountSortLimitOffset() {
    final String sql = "select * from emp order by ename limit 10 offset 5";
    checkRowCount(sql, 9D); // 14 - 5
    checkMaxRowCount(sql, 10d);
  }

  @Test public void testRowCountSortLimitOffsetOnFinite() {
    final String sql = "select * from (select * from emp limit 12)\n"
        + "order by ename limit 20 offset 5";
    checkRowCount(sql, 7d);
    checkMaxRowCount(sql, 7d);
  }

  @Test public void testRowCountAggregate() {
    final String sql = "select deptno from emp group by deptno";
    checkRowCount(sql, 1.4D);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountAggregateGroupingSets() {
    final String sql = "select deptno from emp\n"
        + "group by grouping sets ((deptno), (empno, deptno))";
    checkRowCount(sql, 2.8D); // EMP_SIZE / 10 * 2
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountAggregateGroupingSetsOneEmpty() {
    final String sql = "select deptno from emp\n"
        + "group by grouping sets ((deptno), ())";
    checkRowCount(sql, 2.8D);
    checkMaxRowCount(sql, Double.POSITIVE_INFINITY);
  }

  @Test public void testRowCountAggregateEmptyKey() {
    final String sql = "select count(*) from emp";
    checkRowCount(sql, 1D);
    checkMaxRowCount(sql, 1D);
  }

  @Test public void testRowCountAggregateEmptyKeyOnEmptyTable() {
    final String sql = "select count(*) from (select * from emp limit 0)";
    checkRowCount(sql, 1D);
    checkMaxRowCount(sql, 1D);
  }

  private void checkFilterSelectivity(
      String sql,
      double expected) {
    RelNode rel = convertSql(sql);
    Double result = RelMetadataQuery.getSelectivity(rel, null);
    assertTrue(result != null);
    assertEquals(expected, result, EPSILON);
  }

  @Test public void testSelectivityIsNotNullFilter() {
    checkFilterSelectivity(
        "select * from emp where mgr is not null",
        DEFAULT_NOTNULL_SELECTIVITY);
  }

  @Test public void testSelectivityIsNotNullFilterOnNotNullColumn() {
    checkFilterSelectivity(
        "select * from emp where deptno is not null",
        1.0d);
  }

  @Test public void testSelectivityComparisonFilter() {
    checkFilterSelectivity(
        "select * from emp where deptno > 10",
        DEFAULT_COMP_SELECTIVITY);
  }

  @Test public void testSelectivityAndFilter() {
    checkFilterSelectivity(
        "select * from emp where ename = 'foo' and deptno = 10",
        DEFAULT_EQUAL_SELECTIVITY_SQUARED);
  }

  @Test public void testSelectivityOrFilter() {
    checkFilterSelectivity(
        "select * from emp where ename = 'foo' or deptno = 10",
        DEFAULT_SELECTIVITY);
  }

  @Test public void testSelectivityJoin() {
    checkFilterSelectivity(
        "select * from emp join dept using (deptno) where ename = 'foo'",
        DEFAULT_EQUAL_SELECTIVITY);
  }

  private void checkRelSelectivity(
      RelNode rel,
      double expected) {
    Double result = RelMetadataQuery.getSelectivity(rel, null);
    assertTrue(result != null);
    assertEquals(expected, result, EPSILON);
  }

  @Test public void testSelectivityRedundantFilter() {
    RelNode rel = convertSql("select * from emp where deptno = 10");
    checkRelSelectivity(rel, DEFAULT_EQUAL_SELECTIVITY);
  }

  @Test public void testSelectivitySort() {
    RelNode rel =
        convertSql("select * from emp where deptno = 10"
            + "order by ename");
    checkRelSelectivity(rel, DEFAULT_EQUAL_SELECTIVITY);
  }

  @Test public void testSelectivityUnion() {
    RelNode rel =
        convertSql("select * from (\n"
            + "  select * from emp union all select * from emp) "
            + "where deptno = 10");
    checkRelSelectivity(rel, DEFAULT_EQUAL_SELECTIVITY);
  }

  @Test public void testSelectivityAgg() {
    RelNode rel =
        convertSql("select deptno, count(*) from emp where deptno > 10 "
            + "group by deptno having count(*) = 0");
    checkRelSelectivity(
        rel,
        DEFAULT_COMP_SELECTIVITY * DEFAULT_EQUAL_SELECTIVITY);
  }

  /** Checks that we can cache a metadata request that includes a null
   * argument. */
  @Test public void testSelectivityAggCached() {
    RelNode rel =
        convertSql("select deptno, count(*) from emp where deptno > 10 "
            + "group by deptno having count(*) = 0");
    rel.getCluster().setMetadataProvider(
        new CachingRelMetadataProvider(
            rel.getCluster().getMetadataProvider(),
            rel.getCluster().getPlanner()));
    Double result = RelMetadataQuery.getSelectivity(rel, null);
    assertThat(result,
        nearTo(DEFAULT_COMP_SELECTIVITY * DEFAULT_EQUAL_SELECTIVITY, EPSILON));
  }

  @Test public void testDistinctRowCountTable() {
    // no unique key information is available so return null
    RelNode rel = convertSql("select * from emp where deptno = 10");
    ImmutableBitSet groupKey =
        ImmutableBitSet.of(rel.getRowType().getFieldNames().indexOf("DEPTNO"));
    Double result =
        RelMetadataQuery.getDistinctRowCount(
            rel, groupKey, null);
    assertThat(result, nullValue());
  }

  @Test public void testDistinctRowCountTableEmptyKey() {
    RelNode rel = convertSql("select * from emp where deptno = 10");
    ImmutableBitSet groupKey = ImmutableBitSet.of(); // empty key
    Double result =
        RelMetadataQuery.getDistinctRowCount(
            rel, groupKey, null);
    assertThat(result, is(1D));
  }

  /** Asserts that {@link RelMetadataQuery#getUniqueKeys(RelNode)}
   * and {@link RelMetadataQuery#areColumnsUnique(RelNode, ImmutableBitSet)}
   * return consistent results. */
  private void assertUniqueConsistent(RelNode rel) {
    Set<ImmutableBitSet> uniqueKeys = RelMetadataQuery.getUniqueKeys(rel);
    final ImmutableBitSet allCols =
        ImmutableBitSet.range(0, rel.getRowType().getFieldCount());
    for (ImmutableBitSet key : allCols.powerSet()) {
      Boolean result2 = RelMetadataQuery.areColumnsUnique(rel, key);
      assertTrue(result2 == null || result2 == isUnique(uniqueKeys, key));
    }
  }

  /** Returns whether {@code keys} is unique, that is, whether it or a superset
   * is in {@code keySets}. */
  private boolean isUnique(Set<ImmutableBitSet> uniqueKeys, ImmutableBitSet key) {
    for (ImmutableBitSet uniqueKey : uniqueKeys) {
      if (key.contains(uniqueKey)) {
        return true;
      }
    }
    return false;
  }

  /** Test case for
   * <a href="https://issues.apache.org/jira/browse/CALCITE-509">[CALCITE-509]
   * "RelMdColumnUniqueness uses ImmutableBitSet.Builder twice, gets
   * NullPointerException"</a>. */
  @Test public void testJoinUniqueKeys() {
    RelNode rel = convertSql("select * from emp join dept using (deptno)");
    Set<ImmutableBitSet> result = RelMetadataQuery.getUniqueKeys(rel);
    assertThat(result.isEmpty(), is(true));
    assertUniqueConsistent(rel);
  }

  @Test public void testGroupByEmptyUniqueKeys() {
    RelNode rel = convertSql("select count(*) from emp");
    Set<ImmutableBitSet> result = RelMetadataQuery.getUniqueKeys(rel);
    assertThat(result,
        CoreMatchers.<Set<ImmutableBitSet>>equalTo(
            ImmutableSet.of(ImmutableBitSet.of())));
    assertUniqueConsistent(rel);
  }

  @Test public void testGroupByEmptyHavingUniqueKeys() {
    RelNode rel = convertSql("select count(*) from emp where 1 = 1");
    Set<ImmutableBitSet> result = RelMetadataQuery.getUniqueKeys(rel);
    assertThat(result,
        CoreMatchers.<Set<ImmutableBitSet>>equalTo(
            ImmutableSet.of(ImmutableBitSet.of())));
    assertUniqueConsistent(rel);
  }

  @Test public void testGroupBy() {
    RelNode rel = convertSql("select deptno, count(*), sum(sal) from emp\n"
            + "group by deptno");
    Set<ImmutableBitSet> result = RelMetadataQuery.getUniqueKeys(rel);
    assertThat(result,
        CoreMatchers.<Set<ImmutableBitSet>>equalTo(
            ImmutableSet.of(ImmutableBitSet.of(0))));
    assertUniqueConsistent(rel);
  }

  @Test public void testUnion() {
    RelNode rel = convertSql("select deptno from emp\n"
            + "union\n"
            + "select deptno from dept");
    Set<ImmutableBitSet> result = RelMetadataQuery.getUniqueKeys(rel);
    assertThat(result,
        CoreMatchers.<Set<ImmutableBitSet>>equalTo(
            ImmutableSet.of(ImmutableBitSet.of(0))));
    assertUniqueConsistent(rel);
  }

  @Test public void testCustomProvider() {
    final List<String> buf = Lists.newArrayList();
    ColTypeImpl.THREAD_LIST.set(buf);

    RelNode rel =
        convertSql("select deptno, count(*) from emp where deptno > 10 "
            + "group by deptno having count(*) = 0");
    rel.getCluster().setMetadataProvider(
        ChainedRelMetadataProvider.of(
            ImmutableList.of(
                ColTypeImpl.SOURCE, rel.getCluster().getMetadataProvider())));

    // Top node is a filter. Its metadata uses getColType(RelNode, int).
    assertThat(rel, instanceOf(LogicalFilter.class));
    assertThat(rel.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-rel"));
    assertThat(rel.metadata(ColType.class).getColType(1),
        equalTo("EXPR$1-rel"));

    // Next node is an aggregate. Its metadata uses
    // getColType(LogicalAggregate, int).
    final RelNode input = rel.getInput(0);
    assertThat(input, instanceOf(LogicalAggregate.class));
    assertThat(input.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-agg"));

    // There is no caching. Another request causes another call to the provider.
    assertThat(buf.toString(), equalTo("[DEPTNO-rel, EXPR$1-rel, DEPTNO-agg]"));
    assertThat(buf.size(), equalTo(3));
    assertThat(input.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-agg"));
    assertThat(buf.size(), equalTo(4));

    // Now add a cache. Only the first request for each piece of metadata
    // generates a new call to the provider.
    final RelOptPlanner planner = rel.getCluster().getPlanner();
    rel.getCluster().setMetadataProvider(
        new CachingRelMetadataProvider(
            rel.getCluster().getMetadataProvider(), planner));
    assertThat(input.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-agg"));
    assertThat(buf.size(), equalTo(5));
    assertThat(input.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-agg"));
    assertThat(buf.size(), equalTo(5));
    assertThat(input.metadata(ColType.class).getColType(1),
        equalTo("EXPR$1-agg"));
    assertThat(buf.size(), equalTo(6));
    assertThat(input.metadata(ColType.class).getColType(1),
        equalTo("EXPR$1-agg"));
    assertThat(buf.size(), equalTo(6));
    assertThat(input.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-agg"));
    assertThat(buf.size(), equalTo(6));

    // With a different timestamp, a metadata item is re-computed on first call.
    long timestamp = planner.getRelMetadataTimestamp(rel);
    assertThat(timestamp, equalTo(0L));
    ((MockRelOptPlanner) planner).setRelMetadataTimestamp(timestamp + 1);
    assertThat(input.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-agg"));
    assertThat(buf.size(), equalTo(7));
    assertThat(input.metadata(ColType.class).getColType(0),
        equalTo("DEPTNO-agg"));
    assertThat(buf.size(), equalTo(7));
  }

  /** Unit test for
   * {@link org.apache.calcite.rel.metadata.RelMdCollation#project}
   * and other helper functions for deducing collations. */
  @Test public void testCollation() {
    final Project rel = (Project) convertSql("select * from emp, dept");
    final Join join = (Join) rel.getInput();
    final RelOptTable empTable = join.getInput(0).getTable();
    final RelOptTable deptTable = join.getInput(1).getTable();
    Frameworks.withPlanner(
        new Frameworks.PlannerAction<Void>() {
          public Void apply(RelOptCluster cluster,
              RelOptSchema relOptSchema,
              SchemaPlus rootSchema) {
            checkCollation(cluster, empTable, deptTable);
            return null;
          }
        });
  }

  private void checkCollation(RelOptCluster cluster, RelOptTable empTable,
      RelOptTable deptTable) {
    final RexBuilder rexBuilder = cluster.getRexBuilder();
    final LogicalTableScan empScan = LogicalTableScan.create(cluster, empTable);

    List<RelCollation> collations =
        RelMdCollation.table(empScan.getTable());
    assertThat(collations.size(), equalTo(0));

    // ORDER BY field#0 ASC, field#1 ASC
    final RelCollation collation =
        RelCollations.of(new RelFieldCollation(0), new RelFieldCollation(1));
    collations = RelMdCollation.sort(collation);
    assertThat(collations.size(), equalTo(1));
    assertThat(collations.get(0).getFieldCollations().size(), equalTo(2));

    final Sort empSort = LogicalSort.create(empScan, collation, null, null);

    final List<RexNode> projects =
        ImmutableList.of(rexBuilder.makeInputRef(empSort, 1),
            rexBuilder.makeLiteral("foo"),
            rexBuilder.makeInputRef(empSort, 0),
            rexBuilder.makeCall(SqlStdOperatorTable.MINUS,
                rexBuilder.makeInputRef(empSort, 0),
                rexBuilder.makeInputRef(empSort, 3)));

    collations = RelMdCollation.project(empSort, projects);
    assertThat(collations.size(), equalTo(1));
    assertThat(collations.get(0).getFieldCollations().size(), equalTo(2));
    assertThat(collations.get(0).getFieldCollations().get(0).getFieldIndex(),
        equalTo(2));
    assertThat(collations.get(0).getFieldCollations().get(1).getFieldIndex(),
        equalTo(0));

    final LogicalProject project = LogicalProject.create(empSort, projects,
        ImmutableList.of("a", "b", "c", "d"));

    final LogicalTableScan deptScan =
        LogicalTableScan.create(cluster, deptTable);

    final RelCollation deptCollation =
        RelCollations.of(new RelFieldCollation(0), new RelFieldCollation(1));
    final Sort deptSort =
        LogicalSort.create(deptScan, deptCollation, null, null);

    final ImmutableIntList leftKeys = ImmutableIntList.of(2);
    final ImmutableIntList rightKeys = ImmutableIntList.of(0);
    final EnumerableMergeJoin join;
    try {
      join = EnumerableMergeJoin.create(project, deptSort,
          rexBuilder.makeLiteral(true), leftKeys, rightKeys, JoinRelType.INNER);
    } catch (InvalidRelException e) {
      throw Throwables.propagate(e);
    }
    collations =
        RelMdCollation.mergeJoin(project, deptSort, leftKeys, rightKeys);
    assertThat(collations,
        equalTo(join.getTraitSet().getTraits(RelCollationTraitDef.INSTANCE)));

    // Values (empty)
    collations = RelMdCollation.values(empTable.getRowType(),
        ImmutableList.<ImmutableList<RexLiteral>>of());
    assertThat(collations.toString(),
        equalTo("[[0, 1, 2, 3, 4, 5, 6, 7, 8], "
            + "[1, 2, 3, 4, 5, 6, 7, 8], "
            + "[2, 3, 4, 5, 6, 7, 8], "
            + "[3, 4, 5, 6, 7, 8], "
            + "[4, 5, 6, 7, 8], "
            + "[5, 6, 7, 8], "
            + "[6, 7, 8], "
            + "[7, 8], "
            + "[8]]"));

    final LogicalValues emptyValues =
        LogicalValues.createEmpty(cluster, empTable.getRowType());
    assertThat(RelMetadataQuery.collations(emptyValues), equalTo(collations));

    // Values (non-empty)
    final RelDataType rowType = cluster.getTypeFactory().builder()
        .add("a", SqlTypeName.INTEGER)
        .add("b", SqlTypeName.INTEGER)
        .add("c", SqlTypeName.INTEGER)
        .add("d", SqlTypeName.INTEGER)
        .build();
    final ImmutableList.Builder<ImmutableList<RexLiteral>> tuples =
        ImmutableList.builder();
    // sort keys are [a], [a, b], [a, b, c], [a, b, c, d], [a, c], [b], [b, a],
    //   [b, d]
    // algorithm deduces [a, b, c, d], [b, d] which is a useful sub-set
    addRow(tuples, rexBuilder, 1, 1, 1, 1);
    addRow(tuples, rexBuilder, 1, 2, 0, 3);
    addRow(tuples, rexBuilder, 2, 3, 2, 2);
    addRow(tuples, rexBuilder, 3, 3, 1, 4);
    collations = RelMdCollation.values(rowType, tuples.build());
    assertThat(collations.toString(),
        equalTo("[[0, 1, 2, 3], [1, 3]]"));

    final LogicalValues values =
        LogicalValues.create(cluster, rowType, tuples.build());
    assertThat(RelMetadataQuery.collations(values), equalTo(collations));
  }

  private void addRow(ImmutableList.Builder<ImmutableList<RexLiteral>> builder,
      RexBuilder rexBuilder, Object... values) {
    ImmutableList.Builder<RexLiteral> b = ImmutableList.builder();
    for (Object value : values) {
      final RexLiteral literal;
      if (value == null) {
        literal = (RexLiteral) rexBuilder.makeNullLiteral(SqlTypeName.VARCHAR);
      } else if (value instanceof Integer) {
        literal = rexBuilder.makeExactLiteral(
            BigDecimal.valueOf((Integer) value));
      } else {
        literal = rexBuilder.makeLiteral((String) value);
      }
      b.add(literal);
    }
    builder.add(b.build());
  }

  /** Unit test for
   * {@link org.apache.calcite.rel.metadata.RelMetadataQuery#getAverageColumnSizes(org.apache.calcite.rel.RelNode)},
   * {@link org.apache.calcite.rel.metadata.RelMetadataQuery#getAverageRowSize(org.apache.calcite.rel.RelNode)}. */
  @Test public void testAverageRowSize() {
    final Project rel = (Project) convertSql("select * from emp, dept");
    final Join join = (Join) rel.getInput();
    final RelOptTable empTable = join.getInput(0).getTable();
    final RelOptTable deptTable = join.getInput(1).getTable();
    Frameworks.withPlanner(
        new Frameworks.PlannerAction<Void>() {
          public Void apply(RelOptCluster cluster,
              RelOptSchema relOptSchema,
              SchemaPlus rootSchema) {
            checkAverageRowSize(cluster, empTable, deptTable);
            return null;
          }
        });
  }

  private void checkAverageRowSize(RelOptCluster cluster, RelOptTable empTable,
      RelOptTable deptTable) {
    final RexBuilder rexBuilder = cluster.getRexBuilder();
    final LogicalTableScan empScan = LogicalTableScan.create(cluster, empTable);

    Double rowSize = RelMetadataQuery.getAverageRowSize(empScan);
    List<Double> columnSizes = RelMetadataQuery.getAverageColumnSizes(empScan);

    assertThat(columnSizes.size(),
        equalTo(empScan.getRowType().getFieldCount()));
    assertThat(columnSizes,
        equalTo(Arrays.asList(4.0, 40.0, 20.0, 4.0, 8.0, 4.0, 4.0, 4.0, 1.0)));
    assertThat(rowSize, equalTo(89.0));

    // Empty values
    final LogicalValues emptyValues =
        LogicalValues.createEmpty(cluster, empTable.getRowType());
    rowSize = RelMetadataQuery.getAverageRowSize(emptyValues);
    columnSizes = RelMetadataQuery.getAverageColumnSizes(emptyValues);
    assertThat(columnSizes.size(),
        equalTo(emptyValues.getRowType().getFieldCount()));
    assertThat(columnSizes,
        equalTo(Arrays.asList(4.0, 40.0, 20.0, 4.0, 8.0, 4.0, 4.0, 4.0, 1.0)));
    assertThat(rowSize, equalTo(89.0));

    // Values
    final RelDataType rowType = cluster.getTypeFactory().builder()
        .add("a", SqlTypeName.INTEGER)
        .add("b", SqlTypeName.VARCHAR)
        .add("c", SqlTypeName.VARCHAR)
        .build();
    final ImmutableList.Builder<ImmutableList<RexLiteral>> tuples =
        ImmutableList.builder();
    addRow(tuples, rexBuilder, 1, "1234567890", "ABC");
    addRow(tuples, rexBuilder, 2, "1",          "A");
    addRow(tuples, rexBuilder, 3, "2",          null);
    final LogicalValues values =
        LogicalValues.create(cluster, rowType, tuples.build());
    rowSize = RelMetadataQuery.getAverageRowSize(values);
    columnSizes = RelMetadataQuery.getAverageColumnSizes(values);
    assertThat(columnSizes.size(),
        equalTo(values.getRowType().getFieldCount()));
    assertThat(columnSizes, equalTo(Arrays.asList(4.0, 8.0, 3.0)));
    assertThat(rowSize, equalTo(15.0));

    // Union
    final LogicalUnion union =
        LogicalUnion.create(ImmutableList.<RelNode>of(empScan, emptyValues),
            true);
    rowSize = RelMetadataQuery.getAverageRowSize(union);
    columnSizes = RelMetadataQuery.getAverageColumnSizes(union);
    assertThat(columnSizes.size(), equalTo(9));
    assertThat(columnSizes,
        equalTo(Arrays.asList(4.0, 40.0, 20.0, 4.0, 8.0, 4.0, 4.0, 4.0, 1.0)));
    assertThat(rowSize, equalTo(89.0));

    // Filter
    final LogicalTableScan deptScan =
        LogicalTableScan.create(cluster, deptTable);
    final LogicalFilter filter =
        LogicalFilter.create(deptScan,
            rexBuilder.makeCall(SqlStdOperatorTable.LESS_THAN,
                rexBuilder.makeInputRef(deptScan, 0),
                rexBuilder.makeExactLiteral(BigDecimal.TEN)));
    rowSize = RelMetadataQuery.getAverageRowSize(filter);
    columnSizes = RelMetadataQuery.getAverageColumnSizes(filter);
    assertThat(columnSizes.size(), equalTo(2));
    assertThat(columnSizes, equalTo(Arrays.asList(4.0, 20.0)));
    assertThat(rowSize, equalTo(24.0));

    // Project
    final LogicalProject deptProject =
        LogicalProject.create(filter,
            ImmutableList.of(
                rexBuilder.makeInputRef(filter, 0),
                rexBuilder.makeInputRef(filter, 1),
                rexBuilder.makeCall(SqlStdOperatorTable.PLUS,
                    rexBuilder.makeInputRef(filter, 0),
                    rexBuilder.makeExactLiteral(BigDecimal.ONE)),
                rexBuilder.makeCall(SqlStdOperatorTable.CHAR_LENGTH,
                    rexBuilder.makeInputRef(filter, 1))),
            (List<String>) null);
    rowSize = RelMetadataQuery.getAverageRowSize(deptProject);
    columnSizes = RelMetadataQuery.getAverageColumnSizes(deptProject);
    assertThat(columnSizes.size(), equalTo(4));
    assertThat(columnSizes, equalTo(Arrays.asList(4.0, 20.0, 4.0, 4.0)));
    assertThat(rowSize, equalTo(32.0));

    // Join
    final LogicalJoin join =
        LogicalJoin.create(empScan, deptProject, rexBuilder.makeLiteral(true),
            JoinRelType.INNER, ImmutableSet.<String>of());
    rowSize = RelMetadataQuery.getAverageRowSize(join);
    columnSizes = RelMetadataQuery.getAverageColumnSizes(join);
    assertThat(columnSizes.size(), equalTo(13));
    assertThat(columnSizes,
        equalTo(
            Arrays.asList(4.0, 40.0, 20.0, 4.0, 8.0, 4.0, 4.0, 4.0, 1.0, 4.0,
                20.0, 4.0, 4.0)));
    assertThat(rowSize, equalTo(121.0));

    // Aggregate
    final LogicalAggregate aggregate =
        LogicalAggregate.create(join, false, ImmutableBitSet.of(2, 0),
            ImmutableList.<ImmutableBitSet>of(),
            ImmutableList.of(
                AggregateCall.create(
                    SqlStdOperatorTable.COUNT, false, ImmutableIntList.of(), -1,
                    2, join, null, null)));
    rowSize = RelMetadataQuery.getAverageRowSize(aggregate);
    columnSizes = RelMetadataQuery.getAverageColumnSizes(aggregate);
    assertThat(columnSizes.size(), equalTo(3));
    assertThat(columnSizes, equalTo(Arrays.asList(4.0, 20.0, 8.0)));
    assertThat(rowSize, equalTo(32.0));

    // Smoke test Parallelism and Memory metadata providers
    assertThat(RelMetadataQuery.memory(aggregate), nullValue());
    assertThat(RelMetadataQuery.cumulativeMemoryWithinPhase(aggregate),
        nullValue());
    assertThat(RelMetadataQuery.cumulativeMemoryWithinPhaseSplit(aggregate),
        nullValue());
    assertThat(RelMetadataQuery.isPhaseTransition(aggregate), is(false));
    assertThat(RelMetadataQuery.splitCount(aggregate), is(1));
  }

  /** Unit test for
   * {@link org.apache.calcite.rel.metadata.RelMdPredicates#getPredicates(SemiJoin)}. */
  @Test public void testPredicates() {
    final Project rel = (Project) convertSql("select * from emp, dept");
    final Join join = (Join) rel.getInput();
    final RelOptTable empTable = join.getInput(0).getTable();
    final RelOptTable deptTable = join.getInput(1).getTable();
    Frameworks.withPlanner(
        new Frameworks.PlannerAction<Void>() {
          public Void apply(RelOptCluster cluster,
              RelOptSchema relOptSchema,
              SchemaPlus rootSchema) {
            checkPredicates(cluster, empTable, deptTable);
            return null;
          }
        });
  }

  private void checkPredicates(RelOptCluster cluster, RelOptTable empTable,
      RelOptTable deptTable) {
    final RexBuilder rexBuilder = cluster.getRexBuilder();
    final LogicalTableScan empScan = LogicalTableScan.create(cluster, empTable);

    RelOptPredicateList predicates =
        RelMetadataQuery.getPulledUpPredicates(empScan);
    assertThat(predicates.pulledUpPredicates.isEmpty(), is(true));

    final LogicalFilter filter =
        LogicalFilter.create(empScan,
            rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
                rexBuilder.makeInputRef(empScan,
                    empScan.getRowType().getFieldNames().indexOf("EMPNO")),
                rexBuilder.makeExactLiteral(BigDecimal.ONE)));

    predicates = RelMetadataQuery.getPulledUpPredicates(filter);
    assertThat(predicates.pulledUpPredicates.toString(), is("[=($0, 1)]"));

    final LogicalTableScan deptScan =
        LogicalTableScan.create(cluster, deptTable);

    final RelDataTypeField leftDeptnoField =
        empScan.getRowType().getFieldList().get(
            empScan.getRowType().getFieldNames().indexOf("DEPTNO"));
    final RelDataTypeField rightDeptnoField =
        deptScan.getRowType().getFieldList().get(
            deptScan.getRowType().getFieldNames().indexOf("DEPTNO"));
    final SemiJoin semiJoin =
        SemiJoin.create(filter, deptScan,
            rexBuilder.makeCall(SqlStdOperatorTable.EQUALS,
                rexBuilder.makeInputRef(leftDeptnoField.getType(),
                    leftDeptnoField.getIndex()),
                rexBuilder.makeInputRef(rightDeptnoField.getType(),
                    rightDeptnoField.getIndex()
                        + empScan.getRowType().getFieldCount())),
            ImmutableIntList.of(leftDeptnoField.getIndex()),
            ImmutableIntList.of(rightDeptnoField.getIndex()
                    + empScan.getRowType().getFieldCount()));

    predicates = RelMetadataQuery.getPulledUpPredicates(semiJoin);
    assertThat(predicates.pulledUpPredicates, sortsAs("[=($0, 1)]"));
    assertThat(predicates.leftInferredPredicates, sortsAs("[]"));
    assertThat(predicates.rightInferredPredicates.isEmpty(), is(true));
  }

  /**
   * Unit test for
   * {@link org.apache.calcite.rel.metadata.RelMdPredicates#getPredicates(Aggregate)}.
   */
  @Test public void testPullUpPredicatesFromAggregation() {
    final String sql = "select a, max(b) from (\n"
        + "  select 1 as a, 2 as b from emp)subq\n"
        + "group by a";
    final Aggregate rel = (Aggregate) convertSql(sql);
    RelOptPredicateList inputSet = RelMetadataQuery.getPulledUpPredicates(rel);
    ImmutableList<RexNode> pulledUpPredicates = inputSet.pulledUpPredicates;
    assertThat(pulledUpPredicates, sortsAs("[=($0, 1)]"));
  }

  @Test public void testPullUpPredicatesOnConstant() {
    final String sql = "select deptno, mgr, x, 'y' as y, z from (\n"
        + "  select deptno, mgr, cast(null as integer) as x, cast('1' as int) as z\n"
        + "  from emp\n"
        + "  where mgr is null and deptno < 10)";
    final RelNode rel = convertSql(sql);
    RelOptPredicateList list = RelMetadataQuery.getPulledUpPredicates(rel);
    assertThat(list.pulledUpPredicates,
        sortsAs("[<($0, 10), =($3, 'y'), =($4, CAST('1'):INTEGER NOT NULL), "
            + "IS NULL($1), IS NULL($2)]"));
  }

  @Test public void testPullUpPredicatesOnNullableConstant() {
    final String sql = "select nullif(1, 1) as c\n"
        + "  from emp\n"
        + "  where mgr is null and deptno < 10";
    final RelNode rel = convertSql(sql);
    RelOptPredicateList list = RelMetadataQuery.getPulledUpPredicates(rel);
    // Uses "IS NOT DISTINCT FROM" rather than "=" because cannot guarantee not null.
    assertThat(list.pulledUpPredicates,
        sortsAs("[IS NOT DISTINCT FROM($0, CASE(=(1, 1), null, 1))]"));
  }

  /**
   * Matcher that succeeds for any collection that, when converted to strings
   * and sorted on those strings, matches the given reference string.
   *
   * <p>Use it as an alternative to {@link CoreMatchers#is} if items in your
   * list might occur in any order.
   *
   * <p>For example:
   *
   * <pre>List&lt;Integer&gt; ints = Arrays.asList(2, 500, 12);
   * assertThat(ints, sortsAs("[12, 2, 500]");</pre>
   */
  static <T> Matcher<Iterable<? extends T>> sortsAs(final String value) {
    return new CustomTypeSafeMatcher<Iterable<? extends T>>(value) {
      protected boolean matchesSafely(Iterable<? extends T> item) {
        final List<String> strings = new ArrayList<>();
        for (T t : item) {
          strings.add(t.toString());
        }
        Collections.sort(strings);
        return value.equals(strings.toString());
      }
    };
  }

  /** Custom metadata interface. */
  public interface ColType extends Metadata {
    String getColType(int column);
  }

  /** A provider for {@link org.apache.calcite.test.RelMetadataTest.ColType} via
   * reflection. */
  public static class ColTypeImpl {
    static final ThreadLocal<List<String>> THREAD_LIST = new ThreadLocal<>();
    static final Method METHOD;
    static {
      try {
        METHOD = ColType.class.getMethod("getColType", int.class);
      } catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }

    public static final RelMetadataProvider SOURCE =
        ReflectiveRelMetadataProvider.reflectiveSource(
            METHOD, new ColTypeImpl());

    /** Implementation of {@link ColType#getColType(int)} for
     * {@link org.apache.calcite.rel.logical.LogicalAggregate}, called via
     * reflection. */
    @SuppressWarnings("UnusedDeclaration")
    public String getColType(Aggregate rel, int column) {
      final String name =
          rel.getRowType().getFieldList().get(column).getName() + "-agg";
      THREAD_LIST.get().add(name);
      return name;
    }

    /** Implementation of {@link ColType#getColType(int)} for
     * {@link RelNode}, called via reflection. */
    @SuppressWarnings("UnusedDeclaration")
    public String getColType(RelNode rel, int column) {
      final String name =
          rel.getRowType().getFieldList().get(column).getName() + "-rel";
      THREAD_LIST.get().add(name);
      return name;
    }
  }
}

// End RelMetadataTest.java
