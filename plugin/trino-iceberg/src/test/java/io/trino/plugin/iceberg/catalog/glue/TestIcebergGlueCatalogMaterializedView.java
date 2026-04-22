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
package io.trino.plugin.iceberg.catalog.glue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.plugin.iceberg.BaseIcebergMaterializedViewTest;
import io.trino.plugin.iceberg.IcebergQueryRunner;
import io.trino.plugin.iceberg.SchemaInitializer;
import io.trino.testing.QueryRunner;
import org.testng.annotations.AfterClass;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetTablesResponse;
import software.amazon.awssdk.services.glue.model.Table;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static org.apache.iceberg.BaseMetastoreTableOperations.METADATA_LOCATION_PROP;

public class TestIcebergGlueCatalogMaterializedView
        extends BaseIcebergMaterializedViewTest
{
    private final String schemaName = "test_iceberg_materialized_view_" + randomNameSuffix();

    private File schemaDirectory;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        this.schemaDirectory = Files.createTempDirectory("test_iceberg").toFile();
        schemaDirectory.deleteOnExit();

        return IcebergQueryRunner.builder()
                .setIcebergProperties(
                        ImmutableMap.of(
                                "iceberg.catalog.type", "glue",
                                "hive.metastore.glue.default-warehouse-dir", schemaDirectory.getAbsolutePath()))
                .setSchemaInitializer(
                        SchemaInitializer.builder()
                                .withClonedTpchTables(ImmutableList.of())
                                .withSchemaName(schemaName)
                                .build())
                .build();
    }

    @Override
    protected String getSchemaDirectory()
    {
        return new File(schemaDirectory, schemaName + ".db").getPath();
    }

    @Override
    protected String getStorageMetadataLocation(String materializedViewName)
    {
        return GlueClient.create()
                .getTable(x -> x
                        .databaseName(schemaName)
                        .name(materializedViewName))
                .table()
                .parameters().get(METADATA_LOCATION_PROP);
    }

    @AfterClass(alwaysRun = true)
    public void cleanup()
    {
        cleanUpSchema(schemaName);
    }

    private static void cleanUpSchema(String schema)
    {
        GlueClient glueClient = GlueClient.create();
        Set<String> tableNames = glueClient
                .getTablesPaginator(x -> x.databaseName(schema))
                .stream()
                .map(GetTablesResponse::tableList)
                .flatMap(Collection::stream)
                .map(Table::name)
                .collect(toImmutableSet());
        glueClient.batchDeleteTable(x -> x
                .databaseName(schema)
                .tablesToDelete(tableNames));
        glueClient.deleteDatabase(x -> x.name(schema));
    }
}
