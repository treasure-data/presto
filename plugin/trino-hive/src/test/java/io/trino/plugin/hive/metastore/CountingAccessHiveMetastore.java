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
package io.trino.plugin.hive.metastore;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.errorprone.annotations.ThreadSafe;
import io.trino.plugin.hive.HiveType;
import io.trino.plugin.hive.PartitionStatistics;
import io.trino.plugin.hive.metastore.HivePrivilegeInfo.HivePrivilege;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.security.RoleGrant;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

@ThreadSafe
public class CountingAccessHiveMetastore
        implements HiveMetastore
{
    private final HiveMetastore delegate;
    private final ConcurrentHashMultiset<MetastoreMethod> methodInvocations = ConcurrentHashMultiset.create();

    public CountingAccessHiveMetastore(HiveMetastore delegate)
    {
        this.delegate = delegate;
    }

    public Multiset<MetastoreMethod> getMethodInvocations()
    {
        return ImmutableMultiset.copyOf(methodInvocations);
    }

    public void resetCounters()
    {
        methodInvocations.clear();
    }

    @Override
    public Optional<Table> getTable(String databaseName, String tableName)
    {
        methodInvocations.add(MetastoreMethod.GET_TABLE);
        return delegate.getTable(databaseName, tableName);
    }

    @Override
    public Map<String, HiveColumnStatistics> getTableColumnStatistics(String databaseName, String tableName, Set<String> columnNames)
    {
        methodInvocations.add(MetastoreMethod.GET_TABLE_STATISTICS);
        return delegate.getTableColumnStatistics(databaseName, tableName, columnNames);
    }

    @Override
    public Map<String, Map<String, HiveColumnStatistics>> getPartitionColumnStatistics(String databaseName, String tableName, Set<String> partitionNames, Set<String> columnNames)
    {
        methodInvocations.add(MetastoreMethod.GET_PARTITION_STATISTICS);
        return delegate.getPartitionColumnStatistics(databaseName, tableName, partitionNames, columnNames);
    }

    @Override
    public void updateTableStatistics(String databaseName, String tableName, OptionalLong acidWriteId, StatisticsUpdateMode mode, PartitionStatistics statisticsUpdate)
    {
        methodInvocations.add(MetastoreMethod.UPDATE_TABLE_STATISTICS);
        delegate.updateTableStatistics(databaseName, tableName, acidWriteId, mode, statisticsUpdate);
    }

    @Override
    public void updatePartitionStatistics(Table table, StatisticsUpdateMode mode, Map<String, PartitionStatistics> partitionUpdates)
    {
        methodInvocations.add(MetastoreMethod.UPDATE_PARTITION_STATISTICS);
        delegate.updatePartitionStatistics(table, mode, partitionUpdates);
    }

    @Override
    public List<TableInfo> getTables(String databaseName)
    {
        methodInvocations.add(MetastoreMethod.GET_ALL_TABLES_FROM_DATABASE);
        return delegate.getTables(databaseName);
    }

    @Override
    public List<String> getTableNamesWithParameters(String databaseName, String parameterKey, ImmutableSet<String> parameterValues)
    {
        methodInvocations.add(MetastoreMethod.GET_TABLE_WITH_PARAMETER);
        return delegate.getTableNamesWithParameters(databaseName, parameterKey, parameterValues);
    }

    @Override
    public List<String> getAllDatabases()
    {
        methodInvocations.add(MetastoreMethod.GET_ALL_DATABASES);
        return delegate.getAllDatabases();
    }

    @Override
    public Optional<Database> getDatabase(String databaseName)
    {
        methodInvocations.add(MetastoreMethod.GET_DATABASE);
        return delegate.getDatabase(databaseName);
    }

    @Override
    public void createDatabase(Database database)
    {
        methodInvocations.add(MetastoreMethod.CREATE_DATABASE);
        delegate.createDatabase(database);
    }

    @Override
    public void dropDatabase(String databaseName, boolean deleteData)
    {
        delegate.dropDatabase(databaseName, deleteData);
    }

    @Override
    public void renameDatabase(String databaseName, String newDatabaseName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDatabaseOwner(String databaseName, HivePrincipal principal)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createTable(Table table, PrincipalPrivileges principalPrivileges)
    {
        methodInvocations.add(MetastoreMethod.CREATE_TABLE);
        delegate.createTable(table, principalPrivileges);
    }

    @Override
    public void dropTable(String databaseName, String tableName, boolean deleteData)
    {
        methodInvocations.add(MetastoreMethod.DROP_TABLE);
        delegate.dropTable(databaseName, tableName, deleteData);
    }

    @Override
    public void replaceTable(String databaseName, String tableName, Table newTable, PrincipalPrivileges principalPrivileges, Map<String, String> environmentContext)
    {
        methodInvocations.add(MetastoreMethod.REPLACE_TABLE);
        delegate.replaceTable(databaseName, tableName, newTable, principalPrivileges, environmentContext);
    }

    @Override
    public void renameTable(String databaseName, String tableName, String newDatabaseName, String newTableName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commentTable(String databaseName, String tableName, Optional<String> comment)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTableOwner(String databaseName, String tableName, HivePrincipal principal)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commentColumn(String databaseName, String tableName, String columnName, Optional<String> comment)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addColumn(String databaseName, String tableName, String columnName, HiveType columnType, String columnComment)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void renameColumn(String databaseName, String tableName, String oldColumnName, String newColumnName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropColumn(String databaseName, String tableName, String columnName)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Partition> getPartition(Table table, List<String> partitionValues)
    {
        methodInvocations.add(MetastoreMethod.GET_PARTITION);
        return delegate.getPartition(table, partitionValues);
    }

    @Override
    public Optional<List<String>> getPartitionNamesByFilter(String databaseName,
            String tableName,
            List<String> columnNames,
            TupleDomain<String> partitionKeysFilter)
    {
        methodInvocations.add(MetastoreMethod.GET_PARTITION_NAMES_BY_FILTER);
        return delegate.getPartitionNamesByFilter(databaseName, tableName, columnNames, partitionKeysFilter);
    }

    @Override
    public Map<String, Optional<Partition>> getPartitionsByNames(Table table, List<String> partitionNames)
    {
        methodInvocations.add(MetastoreMethod.GET_PARTITIONS_BY_NAMES);
        return delegate.getPartitionsByNames(table, partitionNames);
    }

    @Override
    public void addPartitions(String databaseName, String tableName, List<PartitionWithStatistics> partitions)
    {
        methodInvocations.add(MetastoreMethod.ADD_PARTITIONS);
        delegate.addPartitions(databaseName, tableName, partitions);
    }

    @Override
    public void dropPartition(String databaseName, String tableName, List<String> parts, boolean deleteData)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void alterPartition(String databaseName, String tableName, PartitionWithStatistics partition)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createRole(String role, String grantor)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropRole(String role)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> listRoles()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void grantRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void revokeRoles(Set<String> roles, Set<HivePrincipal> grantees, boolean adminOption, HivePrincipal grantor)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<RoleGrant> listRoleGrants(HivePrincipal principal)
    {
        return Set.of();
    }

    @Override
    public void grantTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, HivePrincipal grantor, Set<HivePrivilege> privileges, boolean grantOption)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void revokeTablePrivileges(String databaseName, String tableName, String tableOwner, HivePrincipal grantee, HivePrincipal grantor, Set<HivePrivilege> privileges, boolean grantOption)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<HivePrivilegeInfo> listTablePrivileges(String databaseName, String tableName, Optional<String> tableOwner, Optional<HivePrincipal> principal)
    {
        return Set.of();
    }
}
