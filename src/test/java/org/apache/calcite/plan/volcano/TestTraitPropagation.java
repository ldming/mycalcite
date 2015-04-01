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
package org.apache.calcite.plan.volcano;

import static org.junit.Assert.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.Test;

import com.google.common.collect.ImmutableList;


import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableTableScan;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptAbstractTable;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptQuery;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTrait;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.AbstractConverter.ExpandConversionRule;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.server.CalciteServerStatement;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RuleSet;
import org.apache.calcite.tools.RuleSets;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Tests that determine whether trait propagation work in Volcano Planner
 */
public class TestTraitPropagation {

  static final Convention PHYSICAL =
      new Convention.Impl("PHYSICAL", Phys.class);
  static final RelCollation COLLATION =
      RelCollations.of(new RelFieldCollation(0,
        RelFieldCollation.Direction.ASCENDING,
        RelFieldCollation.NullDirection.FIRST));

  static final RuleSet RULES_NO_HACK = RuleSets.ofList(
      PhysAggRule.INSTANCE, //
      PhysProjRule.INSTANCE, //
      PhysTableRule.INSTANCE, //
      PhysSortRule.INSTANCE, //
      ExpandConversionRule.INSTANCE //
  );

  static final RuleSet RULES_HACK = RuleSets.ofList(
      PhysAggRule.INSTANCE, //
      PhysProjRule.INSTANCE_HACK, //
      PhysTableRule.INSTANCE, //
      PhysSortRule.INSTANCE, //
      ExpandConversionRule.INSTANCE //
  );

  @Test
  public void withoutHack() throws Exception {
    RelNode planned = run(new PropAction(), RULES_NO_HACK);
    System.out.println(RelOptUtil.dumpPlan("LOGICAL PLAN", planned, false,
        SqlExplainLevel.ALL_ATTRIBUTES));
    assertEquals("Sortedness was not propagated", 3,
        RelMetadataQuery.getCumulativeCost(planned).getRows(), 0);
  }

  @Test
  public void withHack() throws Exception {
    RelNode planned = run(new PropAction(), RULES_HACK);
    System.out.println(RelOptUtil.dumpPlan("LOGICAL PLAN", planned, false,
        SqlExplainLevel.ALL_ATTRIBUTES));
    assertEquals("Sortedness was not propagated", 3,
        RelMetadataQuery.getCumulativeCost(planned).getRows(), 0);
  }

  /**
   * Materialized anonymous class for simplicity
   */
  private class PropAction {
    public RelNode apply(RelOptCluster cluster, RelOptSchema relOptSchema,
        SchemaPlus rootSchema) {
      final RelDataTypeFactory typeFactory = cluster.getTypeFactory();
      final RexBuilder rexBuilder = cluster.getRexBuilder();
      final RelOptPlanner planner = cluster.getPlanner();

      final RelDataType stringType = typeFactory.createJavaType(String.class);
      final RelDataType integerType = typeFactory.createJavaType(Integer.class);
      final RelDataType sqlBigInt = typeFactory
          .createSqlType(SqlTypeName.BIGINT);

      // SELECT * from T;
      final Table table = new AbstractTable() {
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
          return typeFactory.builder().add("s", stringType)
              .add("i", integerType).build();
        }
      };

      final RelOptAbstractTable t1 = new RelOptAbstractTable(relOptSchema,
          "t1", table.getRowType(typeFactory)) {
      };

      final RelNode rt1 = new EnumerableTableScan(cluster,
          cluster.traitSetOf(EnumerableConvention.INSTANCE), t1,
          Object[].class);

      // project s column
      RelNode project = new LogicalProject(cluster,
          cluster.traitSetOf(Convention.NONE), rt1,
          ImmutableList.of(
              (RexNode) rexBuilder.makeInputRef(stringType, 0),
              rexBuilder.makeInputRef(integerType, 1)),
          typeFactory.builder().add("s", stringType).add("i", integerType)
          .build());

      // aggregate on s, count
      AggregateCall aggCall = new AggregateCall(SqlStdOperatorTable.COUNT,
          false, Collections.singletonList(1),
          sqlBigInt, "cnt");
      RelNode agg = new LogicalAggregate(cluster,
          cluster.traitSetOf(Convention.NONE), project, false,
          ImmutableBitSet.of(0), null, Collections.singletonList(aggCall));

      final RelNode rootRel = agg;

      RelOptUtil.dumpPlan("LOGICAL PLAN", rootRel, false,
          SqlExplainLevel.DIGEST_ATTRIBUTES);

      RelTraitSet desiredTraits = rootRel.getTraitSet().replace(PHYSICAL);
      final RelNode rootRel2 = planner.changeTraits(rootRel, desiredTraits);
      planner.setRoot(rootRel2);
      return planner.findBestExp();
    }
  }


  /* RULES */
  /** Rule for PhysAgg */
  private static class PhysAggRule extends RelOptRule {
    static final PhysAggRule INSTANCE = new PhysAggRule();

    private PhysAggRule() {
      super(anyChild(LogicalAggregate.class), "PhysAgg");
    }

    public void onMatch(RelOptRuleCall call) {
      RelTraitSet empty = call.getPlanner().emptyTraitSet();
      LogicalAggregate rel = (LogicalAggregate) call.rel(0);
      assert rel.getGroupSet().cardinality() == 1;
      int aggIndex = rel.getGroupSet().iterator().next();
      RelTrait collation = RelCollations.of(new RelFieldCollation(aggIndex,
          RelFieldCollation.Direction.ASCENDING,
          RelFieldCollation.NullDirection.FIRST));
      RelTraitSet desiredTraits = empty.replace(PHYSICAL).replace(collation);
      RelNode convertedInput = convert(rel.getInput(), desiredTraits);
      call.transformTo(new PhysAgg(rel.getCluster(), empty.replace(PHYSICAL),
          convertedInput, rel.indicator, rel
          .getGroupSet(), rel.getGroupSets(), rel.getAggCallList()));
    }
  }

  /** Rule for PhysProj */
  private static class PhysProjRule extends RelOptRule {
    static final PhysProjRule INSTANCE = new PhysProjRule(false);
    static final PhysProjRule INSTANCE_HACK = new PhysProjRule(true);

    final boolean subsetHack;

    private PhysProjRule(boolean subsetHack) {
      super(RelOptRule.operand(LogicalProject.class,
          anyChild(RelNode.class)), "PhysProj");
      this.subsetHack = subsetHack;
    }

    public void onMatch(RelOptRuleCall call) {
      RelTraitSet empty = call.getPlanner().emptyTraitSet();
      LogicalProject rel = (LogicalProject) call.rel(0);
      RelNode input = convert(rel.getInput(), empty.replace(PHYSICAL));


      if (subsetHack && input instanceof RelSubset) {
        RelSubset subset = (RelSubset) input;
        for (RelNode child : subset.getRels()) {
          // skip logical nodes
          if (child.getTraitSet().getTrait(ConventionTraitDef.INSTANCE)
              == Convention.NONE) {
            continue;
          } else {
            RelTraitSet outcome = child.getTraitSet().replace(PHYSICAL);
            call.transformTo(new PhysProj(rel.getCluster(), outcome,
                convert(child, outcome), rel.getChildExps(), rel.getRowType()));
          }
        }
      } else {
        call.transformTo(new PhysProj(rel.getCluster(), input.getTraitSet(),
            input, rel.getChildExps(), rel.getRowType()));
      }

    }
  }

  /** Rule for PhysSort */
  private static class PhysSortRule extends RelOptRule {
    static final PhysSortRule INSTANCE = new PhysSortRule();

    private PhysSortRule() {
      super(anyChild(Sort.class), "PhysSort");
    }

    public boolean matches(RelOptRuleCall call) {
      return !(call.rel(0) instanceof PhysSort);
    }

    public void onMatch(RelOptRuleCall call) {
      RelTraitSet empty = call.getPlanner().emptyTraitSet();
      Sort rel = (Sort) call.rel(0);
      RelNode input = convert(rel.getInput(), empty.plus(PHYSICAL));
      call.transformTo(
          new PhysSort(rel.getCluster(),
          input.getTraitSet().plus(rel.getCollation()),
          input, rel.getCollation(), rel.offset,
          rel.fetch));
    }
  }

  /** Rule for PhysTable */
  private static class PhysTableRule extends RelOptRule {
    static final PhysTableRule INSTANCE = new PhysTableRule();

    private PhysTableRule() {
      super(anyChild(EnumerableTableScan.class), "PhysScan");
    }

    public void onMatch(RelOptRuleCall call) {
      EnumerableTableScan rel = (EnumerableTableScan) call.rel(0);
      call.transformTo(new PhysTable(rel.getCluster()));
    }
  }

  /* RELS */
  /** Market interface for Phys nodes */
  private interface Phys extends RelNode { }

  /** Physical Aggregate RelNode */
  private static class PhysAgg extends Aggregate implements Phys {
    public PhysAgg(RelOptCluster cluster, RelTraitSet traits, RelNode child,
        boolean indicator, ImmutableBitSet groupSet,
        List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
      super(cluster, traits, child, indicator, groupSet, groupSets, aggCalls);

    }

    public Aggregate copy(RelTraitSet traitSet, RelNode input,
        boolean indicator, ImmutableBitSet groupSet,
        List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
      return new PhysAgg(getCluster(), traitSet, input, indicator, groupSet,
          groupSets, aggCalls);
    }

    public RelOptCost computeSelfCost(RelOptPlanner planner) {
      return planner.getCostFactory().makeCost(1, 1, 1);
    }
  }

  /** Physical Project RelNode */
  private static class PhysProj extends Project implements Phys {
    public PhysProj(RelOptCluster cluster, RelTraitSet traits, RelNode child,
        List<RexNode> exps, RelDataType rowType) {
      super(cluster, traits, child, exps, rowType);
    }

    public PhysProj copy(RelTraitSet traitSet, RelNode input,
        List<RexNode> exps, RelDataType rowType) {
      return new PhysProj(getCluster(), traitSet, input, exps, rowType);
    }

    public RelOptCost computeSelfCost(RelOptPlanner planner) {
      return planner.getCostFactory().makeCost(1, 1, 1);
    }
  }

  /** Physical Sort RelNode */
  private static class PhysSort extends Sort implements Phys {
    public PhysSort(RelOptCluster cluster, RelTraitSet traits, RelNode child,
        RelCollation collation, RexNode offset,
        RexNode fetch) {
      super(cluster, traits, child, collation, offset, fetch);

    }

    public PhysSort copy(RelTraitSet traitSet, RelNode newInput,
        RelCollation newCollation, RexNode offset,
        RexNode fetch) {
      return new PhysSort(getCluster(), traitSet, newInput, newCollation,
          offset, fetch);
    }

    public RelOptCost computeSelfCost(RelOptPlanner planner) {
      return planner.getCostFactory().makeCost(1, 1, 1);
    }
  }

  /** Physical Table RelNode */
  private static class PhysTable extends AbstractRelNode implements Phys {
    public PhysTable(RelOptCluster cluster) {
      super(cluster, cluster.traitSet().replace(PHYSICAL).replace(COLLATION));
      RelDataTypeFactory typeFactory = cluster.getTypeFactory();
      final RelDataType stringType = typeFactory.createJavaType(String.class);
      final RelDataType integerType = typeFactory.createJavaType(Integer.class);
      this.rowType = typeFactory.builder().add("s", stringType)
          .add("i", integerType).build();
    }

    public RelOptCost computeSelfCost(RelOptPlanner planner) {
      return planner.getCostFactory().makeCost(1, 1, 1);
    }
  }

  /* UTILS */
  public static RelOptRuleOperand anyChild(Class<? extends RelNode> first) {
    return RelOptRule.operand(first, RelOptRule.any());
  }

  // Created so that we can control when the TraitDefs are defined (e.g.
  // before the cluster is created).
  private static RelNode run(PropAction action, RuleSet rules)
      throws Exception {

    FrameworkConfig config = Frameworks.newConfigBuilder()
        .ruleSets(rules).build();

    final Properties info = new Properties();
    final Connection connection = DriverManager
        .getConnection("jdbc:calcite:", info);
    final CalciteServerStatement statement = connection
        .createStatement().unwrap(CalciteServerStatement.class);
    final CalcitePrepare.Context prepareContext =
          statement.createPrepareContext();
    final JavaTypeFactory typeFactory = prepareContext.getTypeFactory();
    CalciteCatalogReader catalogReader =
          new CalciteCatalogReader(prepareContext.getRootSchema(),
              prepareContext.config().caseSensitive(),
              prepareContext.getDefaultSchemaPath(),
              typeFactory);
    final RexBuilder rexBuilder = new RexBuilder(typeFactory);
    final RelOptPlanner planner = new VolcanoPlanner(config.getCostFactory(),
        config.getContext());

    // set up rules before we generate cluster
    planner.clearRelTraitDefs();
    planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE);

    planner.clear();
    for (RelOptRule r : rules) {
      planner.addRule(r);
    }

    final RelOptQuery query = new RelOptQuery(planner);
    final RelOptCluster cluster = query.createCluster(
        rexBuilder.getTypeFactory(), rexBuilder);
    return action.apply(cluster, catalogReader,
        prepareContext.getRootSchema().plus());

  }
}
