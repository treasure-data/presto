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
package io.prestosql.sql.query;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestSetOperations
{
    private QueryAssertions assertions;

    @BeforeClass
    public void init()
    {
        assertions = new QueryAssertions();
    }

    @AfterClass(alwaysRun = true)
    public void teardown()
    {
        assertions.close();
        assertions = null;
    }

    @Test
    public void testExceptInSubquery()
    {
        assertThat(assertions.query(
                "WITH t(id) AS (VALUES 1, 2, 3) " +
                        "SELECT * FROM t WHERE id IN (" +
                        "    VALUES 1, 1, 2, 3" +
                        "    EXCEPT" +
                        "    VALUES 1)"))
                .matches("VALUES 2, 3");

        assertThat(assertions.query(
                "WITH t(id) AS (VALUES 1, 2, 3) " +
                        "SELECT * FROM t WHERE id IN (" +
                        "    VALUES 1, 1, 2, 2, 3" +
                        "    EXCEPT ALL" +
                        "    VALUES 1, 2, 2)"))
                .matches("VALUES 1, 3");
    }

    @Test
    public void testIntersectInSubquery()
    {
        assertThat(assertions.query(
                "WITH t(id) AS (VALUES 1, 2, 3) " +
                        "SELECT * FROM t WHERE id IN (" +
                        "    VALUES 1, 1, 2" +
                        "    INTERSECT" +
                        "    VALUES 2, 3)"))
                .matches("VALUES 2");

        assertThat(assertions.query(
                "WITH t(id) AS (VALUES 1, 2, 3) " +
                        "SELECT * FROM t WHERE id IN (" +
                        "    VALUES 1, 1, 2" +
                        "    INTERSECT ALL" +
                        "    VALUES 2, 3)"))
                .matches("VALUES 2");
    }
}
