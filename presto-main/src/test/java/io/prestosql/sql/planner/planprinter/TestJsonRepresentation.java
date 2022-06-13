package io.prestosql.sql.planner.planprinter;

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.prestosql.cost.PlanNodeStatsAndCostSummary;
import io.prestosql.cost.StatsAndCosts;
import io.prestosql.execution.TableInfo;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.plugin.tpch.TpchConnectorFactory;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.sql.planner.OrderingScheme;
import io.prestosql.sql.planner.PlanNodeIdAllocator;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.iterative.rule.test.PlanBuilder;
import io.prestosql.sql.planner.plan.DynamicFilterId;
import io.prestosql.sql.planner.plan.JoinNode;
import io.prestosql.sql.planner.plan.PlanFragmentId;
import io.prestosql.sql.planner.plan.PlanNode;
import io.prestosql.testing.LocalQueryRunner;
import io.prestosql.testing.MaterializedResult;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static io.airlift.json.JsonCodec.jsonCodec;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.expression;
import static io.prestosql.sql.planner.plan.AggregationNode.Step.FINAL;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.REPARTITION;
import static io.prestosql.sql.planner.plan.JoinNode.Type.INNER;
import static io.prestosql.sql.planner.planprinter.JsonRenderer.JsonRenderedNode;
import static io.prestosql.sql.planner.planprinter.NodeRepresentation.TypedSymbol;
import static io.prestosql.testing.MaterializedResult.resultBuilder;
import static org.assertj.core.api.Assertions.assertThat;

public class TestJsonRepresentation
{
    private static final JsonCodec<JsonRenderedNode> JSON_RENDERED_NODE_CODEC = jsonCodec(JsonRenderedNode.class);
    private static final TableInfo TABLE_INFO = new TableInfo(
            new QualifiedObjectName("tpch", TINY_SCHEMA_NAME, "orders"),
            TupleDomain.all());

    private LocalQueryRunner queryRunner;

    @BeforeClass
    public void setUp()
    {
        queryRunner = LocalQueryRunner.create(TEST_SESSION);
        queryRunner.createCatalog(TEST_SESSION.getCatalog().get(), new TpchConnectorFactory(1), ImmutableMap.of());
    }

    @Test
    public void testLogicalJsonPlan()
    {
        MaterializedResult actualPlan = queryRunner.execute("EXPLAIN (TYPE LOGICAL, FORMAT JSON) SELECT quantity FROM lineitem limit 10");
        JsonRenderedNode expectedJsonNode = new JsonRenderedNode(
                "6",
                "Output",
                "[quantity]",
                "",
                ImmutableList.of(new JsonRenderedNode(
                        "92",
                        "Limit",
                        "[10]",
                        "",
                        ImmutableList.of(new JsonRenderedNode(
                                "141",
                                "LocalExchange",
                                "[SINGLE] ()",
                                "",
                                ImmutableList.of(new JsonRenderedNode(
                                        "0",
                                        "TableScan",
                                        "[tpch:lineitem:sf0.01]",
                                        "quantity := tpch:quantity\n",
                                        ImmutableList.of(),
                                        ImmutableList.of())),
                                ImmutableList.of())),
                        ImmutableList.of())),
                ImmutableList.of());
        MaterializedResult expectedPlan = resultBuilder(queryRunner.getDefaultSession(), createVarcharType(657))
                .row(JSON_RENDERED_NODE_CODEC.toJson(expectedJsonNode))
                .build();
        assertThat(actualPlan).isEqualTo(expectedPlan);
    }

    @Test
    public void testAggregationPlan()
    {
        assertJsonRepresentation(
                pb -> pb.aggregation(ab -> ab
                        .step(FINAL)
                        .addAggregation(pb.symbol("sum", BIGINT), expression("sum(x)"), ImmutableList.of(BIGINT))
                        .singleGroupingSet(pb.symbol("y", BIGINT), pb.symbol("z", BIGINT))
                        .source(pb.values(pb.symbol("x", BIGINT), pb.symbol("y", BIGINT), pb.symbol("z", BIGINT)))),
                new JsonRenderedNode(
                        "1",
                        "Aggregate(FINAL)[y, z]",
                        "",
                        "sum := sum(\"x\")\n",
                        ImmutableList.of(valuesRepresentation(
                                "0",
                                ImmutableList.of(typedSymbol("x", "bigint"), typedSymbol("y", "bigint"), typedSymbol("z", "bigint")))),
                        ImmutableList.of()));
    }

    @Test
    public void testJoinPlan()
    {
        assertJsonRepresentation(
                pb -> pb.join(
                        INNER,
                        pb.values(pb.symbol("a", BIGINT), pb.symbol("b", BIGINT)),
                        pb.values(pb.symbol("c", BIGINT), pb.symbol("d", BIGINT)),
                        ImmutableList.of(new JoinNode.EquiJoinClause(pb.symbol("a", BIGINT), pb.symbol("d", BIGINT))),
                        ImmutableList.of(pb.symbol("b", BIGINT)),
                        ImmutableList.of(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        ImmutableMap.of(new DynamicFilterId("DF"), pb.symbol("d", BIGINT))),
                new JsonRenderedNode(
                        "2",
                        "InnerJoin",
                        "[(\"a\" = \"d\")]",
                        "dynamicFilterAssignments = {d -> #DF}",
                        ImmutableList.of(
                                valuesRepresentation("0", ImmutableList.of(typedSymbol("a", "bigint"), typedSymbol("b", "bigint"))),
                                valuesRepresentation("1", ImmutableList.of(typedSymbol("c", "bigint"), typedSymbol("d", "bigint")))),
                        ImmutableList.of()));
    }

    @Test
    public void testSourceFragmentIdsInRemoteSource()
    {
        // This test works as a validation to check if descriptor key "sourceFragmentIds" is present
        // because it is being used to create LivePlan in the prestosql's UI.
        assertJsonRepresentation(
                pb -> pb.remoteSource(
                        ImmutableList.of(new PlanFragmentId("1"), new PlanFragmentId("2")),
                        ImmutableList.of(pb.symbol("a", BIGINT), pb.symbol("b", BIGINT)),
                        Optional.empty(),
                        REPARTITION),
                new JsonRenderedNode(
                        "0",
                        "RemoteSource",
                        "[1,2]",
                        "",
                        ImmutableList.of(),
                        ImmutableList.of("1", "2")));
    }

    private static JsonRenderedNode valuesRepresentation(String id, List<TypedSymbol> outputs)
    {
        return new JsonRenderedNode(
                id,
                "Values",
                "",
                "",
                ImmutableList.of(),
                ImmutableList.of());
    }

    private void assertJsonRepresentation(Function<PlanBuilder, PlanNode> sourceNodeSupplier, JsonRenderedNode expectedRepresentation)
    {
        PlanBuilder planBuilder = new PlanBuilder(new PlanNodeIdAllocator(), queryRunner.getMetadata());
        ValuePrinter valuePrinter = new ValuePrinter(queryRunner.getMetadata(), queryRunner.getDefaultSession());
        PlanPrinter planPrinter = new PlanPrinter(
                sourceNodeSupplier.apply(planBuilder),
                planBuilder.getTypes(),
                Optional.empty(),
                scanNode -> TABLE_INFO,
                valuePrinter,
                StatsAndCosts.empty(),
                Optional.empty());
        assertThat(planPrinter.toJson()).isEqualTo(JSON_RENDERED_NODE_CODEC.toJson(expectedRepresentation));
    }

    public static TypedSymbol typedSymbol(String symbol, String type)
    {
        return new TypedSymbol(new Symbol(symbol), type);
    }
}
