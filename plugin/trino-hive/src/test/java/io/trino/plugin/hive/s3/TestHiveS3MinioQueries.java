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
package io.trino.plugin.hive.s3;

import com.google.common.collect.ImmutableMap;
import io.trino.plugin.hive.containers.Hive3MinioDataLake;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DataProviders;
import io.trino.testing.QueryRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Verify.verify;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class TestHiveS3MinioQueries
        extends AbstractTestQueryFramework
{
    private Hive3MinioDataLake hiveMinioDataLake;
    private String bucketName;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        this.bucketName = "test-hive-minio-queries-" + randomNameSuffix();
        this.hiveMinioDataLake = closeAfterClass(new Hive3MinioDataLake(bucketName));
        this.hiveMinioDataLake.start();

        return S3HiveQueryRunner.builder(hiveMinioDataLake)
                .setHiveProperties(ImmutableMap.<String, String>builder()
                        .put("hive.non-managed-table-writes-enabled", "true")
                        .buildOrThrow())
                .build();
    }

    @AfterClass(alwaysRun = true)
    public void cleanUp()
    {
    }

    @Test(dataProviderClass = DataProviders.class, dataProvider = "trueFalse")
    public void testTableLocationTopOfTheBucket(boolean locationWithTrailingSlash)
    {
        String bucketName = "test-bucket-" + randomNameSuffix();
        hiveMinioDataLake.getMinio().createBucket(bucketName);
        hiveMinioDataLake.getMinio().writeFile("We are\nawesome at\nmultiple slashes.".getBytes(UTF_8), bucketName, "a_file");

        String location = "s3://%s%s".formatted(bucketName, locationWithTrailingSlash ? "/" : "");
        String tableName = "test_table_top_of_bucket_%s_%s".formatted(locationWithTrailingSlash, randomNameSuffix());
        String create = "CREATE TABLE %s (a varchar) WITH (format='TEXTFILE', external_location='%s')".formatted(tableName, location);
        if (!locationWithTrailingSlash) {
            assertQueryFails(create, "External location is not a valid file system URI: " + location);
            return;
        }
        assertUpdate(create);

        // Verify location was not normalized along the way. Glue would not do that.
        assertThat(getDeclaredTableLocation(tableName))
                .isEqualTo(location);

        assertThat(query("TABLE " + tableName))
                .matches("VALUES VARCHAR 'We are', 'awesome at', 'multiple slashes.'");

        assertUpdate("INSERT INTO " + tableName + " VALUES 'Aren''t we?'", 1);

        assertThat(query("TABLE " + tableName))
                .matches("VALUES VARCHAR 'We are', 'awesome at', 'multiple slashes.', 'Aren''t we?'");

        assertUpdate("DROP TABLE " + tableName);
    }

    private String getDeclaredTableLocation(String tableName)
    {
        Pattern locationPattern = Pattern.compile(".*external_location = '(.*?)'.*", Pattern.DOTALL);
        Object result = computeScalar("SHOW CREATE TABLE " + tableName);
        Matcher matcher = locationPattern.matcher((String) result);
        if (matcher.find()) {
            String location = matcher.group(1);
            verify(!matcher.find(), "Unexpected second match");
            return location;
        }
        throw new IllegalStateException("Location not found in: " + result);
    }
}
