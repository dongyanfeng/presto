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
package com.facebook.presto.security;

import com.facebook.presto.metadata.QualifiedObjectName;
import com.facebook.presto.spi.CatalogSchemaTableName;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.connector.ConnectorAccessControl;
import com.facebook.presto.spi.connector.ConnectorTransactionHandle;
import com.facebook.presto.spi.security.Identity;
import com.facebook.presto.spi.security.Privilege;
import com.facebook.presto.spi.security.SystemAccessControl;
import com.facebook.presto.spi.security.SystemAccessControlFactory;
import com.facebook.presto.transaction.TransactionId;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.stats.CounterStat;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.inject.Inject;

import java.io.File;
import java.io.FileInputStream;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.spi.StandardErrorCode.SERVER_STARTING_UP;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Maps.fromProperties;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class AccessControlManager
        implements AccessControl
{
    private static final Logger log = Logger.get(AccessControlManager.class);
    private static final File ACCESS_CONTROL_CONFIGURATION = new File("etc/access-control.properties");
    private static final String ACCESS_CONTROL_PROPERTY_NAME = "access-control.name";

    public static final String ALLOW_ALL_ACCESS_CONTROL = "allow-all";

    private final TransactionManager transactionManager;
    private final Map<String, SystemAccessControlFactory> systemAccessControlFactories = new ConcurrentHashMap<>();
    private final Map<String, CatalogAccessControlEntry> catalogAccessControl = new ConcurrentHashMap<>();

    private final AtomicReference<SystemAccessControl> systemAccessControl = new AtomicReference<>(new InitializingSystemAccessControl());
    private final AtomicBoolean systemAccessControlLoading = new AtomicBoolean();

    private final CounterStat authenticationSuccess = new CounterStat();
    private final CounterStat authenticationFail = new CounterStat();
    private final CounterStat authorizationSuccess = new CounterStat();
    private final CounterStat authorizationFail = new CounterStat();

    @Inject
    public AccessControlManager(TransactionManager transactionManager)
    {
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        systemAccessControlFactories.put(ALLOW_ALL_ACCESS_CONTROL, new SystemAccessControlFactory()
        {
            @Override
            public String getName()
            {
                return ALLOW_ALL_ACCESS_CONTROL;
            }

            @Override
            public SystemAccessControl create(Map<String, String> config)
            {
                requireNonNull(config, "config is null");
                checkArgument(config.isEmpty(), "The none access-controller does not support any configuration properties");
                return new AllowAllSystemAccessControl();
            }
        });
    }

    public void addSystemAccessControlFactory(SystemAccessControlFactory accessControlFactory)
    {
        requireNonNull(accessControlFactory, "accessControlFactory is null");

        if (systemAccessControlFactories.putIfAbsent(accessControlFactory.getName(), accessControlFactory) != null) {
            throw new IllegalArgumentException(format("Access control '%s' is already registered", accessControlFactory.getName()));
        }
    }

    public void addCatalogAccessControl(String connectorId, String catalogName, ConnectorAccessControl accessControl)
    {
        requireNonNull(connectorId, "connectorId is null");
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(accessControl, "accessControl is null");

        if (catalogAccessControl.putIfAbsent(catalogName, new CatalogAccessControlEntry(connectorId, accessControl)) != null) {
            throw new IllegalArgumentException(format("Access control for catalog '%s' is already registered", catalogName));
        }
    }

    public void loadSystemAccessControl()
            throws Exception
    {
        if (ACCESS_CONTROL_CONFIGURATION.exists()) {
            Map<String, String> properties = new HashMap<>(loadProperties(ACCESS_CONTROL_CONFIGURATION));

            String accessControlName = properties.remove(ACCESS_CONTROL_PROPERTY_NAME);
            checkArgument(!isNullOrEmpty(accessControlName),
                    "Access control configuration %s does not contain %s", ACCESS_CONTROL_CONFIGURATION.getAbsoluteFile(), ACCESS_CONTROL_PROPERTY_NAME);

            setSystemAccessControl(accessControlName, properties);
        }
        else {
            setSystemAccessControl(ALLOW_ALL_ACCESS_CONTROL, ImmutableMap.of());
        }
    }

    @VisibleForTesting
    protected void setSystemAccessControl(String name, Map<String, String> properties)
    {
        requireNonNull(name, "name is null");
        requireNonNull(properties, "properties is null");

        checkState(systemAccessControlLoading.compareAndSet(false, true), "System access control already initialized");

        log.info("-- Loading system access control --");

        SystemAccessControlFactory systemAccessControlFactory = systemAccessControlFactories.get(name);
        checkState(systemAccessControlFactory != null, "Access control %s is not registered", name);

        SystemAccessControl systemAccessControl = systemAccessControlFactory.create(ImmutableMap.copyOf(properties));
        this.systemAccessControl.set(systemAccessControl);

        log.info("-- Loaded system access control %s --", name);
    }

    @Override
    public void checkCanSetUser(Principal principal, String userName)
    {
        requireNonNull(userName, "userName is null");

        authenticationCheck(() -> systemAccessControl.get().checkCanSetUser(principal, userName));
    }

    @Override
    public void checkCanCreateTable(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanCreateTable(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanCreateTable(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanDropTable(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanDropTable(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanDropTable(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanRenameTable(TransactionId transactionId, Identity identity, QualifiedObjectName tableName, QualifiedObjectName newTableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");
        requireNonNull(newTableName, "newTableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanRenameTable(identity, tableName.asCatalogSchemaTableName(), newTableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanRenameTable(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName(), newTableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanAddColumns(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanAddColumn(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanAddColumn(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanRenameColumn(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanRenameColumn(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanRenameColumn(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanSelectFromTable(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanSelectFromTable(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanSelectFromTable(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanInsertIntoTable(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanInsertIntoTable(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanInsertIntoTable(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanDeleteFromTable(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanDeleteFromTable(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanDeleteFromTable(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanCreateView(TransactionId transactionId, Identity identity, QualifiedObjectName viewName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(viewName, "viewName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanCreateView(identity, viewName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(viewName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanCreateView(entry.getTransactionHandle(transactionId), identity, viewName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanDropView(TransactionId transactionId, Identity identity, QualifiedObjectName viewName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(viewName, "viewName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanDropView(identity, viewName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(viewName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanDropView(entry.getTransactionHandle(transactionId), identity, viewName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanSelectFromView(TransactionId transactionId, Identity identity, QualifiedObjectName viewName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(viewName, "viewName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanSelectFromView(identity, viewName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(viewName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanSelectFromView(entry.getTransactionHandle(transactionId), identity, viewName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanCreateViewWithSelectFromTable(TransactionId transactionId, Identity identity, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanCreateViewWithSelectFromTable(identity, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanCreateViewWithSelectFromTable(entry.getTransactionHandle(transactionId), identity, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanCreateViewWithSelectFromView(TransactionId transactionId, Identity identity, QualifiedObjectName viewName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(viewName, "viewName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanCreateViewWithSelectFromView(identity, viewName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(viewName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanCreateViewWithSelectFromView(entry.getTransactionHandle(transactionId), identity, viewName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanGrantTablePrivilege(TransactionId transactionId, Identity identity, Privilege privilege, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");
        requireNonNull(privilege, "privilege is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanGrantTablePrivilege(identity, privilege, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanGrantTablePrivilege(entry.getTransactionHandle(transactionId), identity, privilege, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanRevokeTablePrivilege(TransactionId transactionId, Identity identity, Privilege privilege, QualifiedObjectName tableName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(tableName, "tableName is null");
        requireNonNull(privilege, "privilege is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanRevokeTablePrivilege(identity, privilege, tableName.asCatalogSchemaTableName()));

        CatalogAccessControlEntry entry = catalogAccessControl.get(tableName.getCatalogName());
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanRevokeTablePrivilege(entry.getTransactionHandle(transactionId), identity, privilege, tableName.asSchemaTableName()));
        }
    }

    @Override
    public void checkCanSetSystemSessionProperty(Identity identity, String propertyName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(propertyName, "propertyName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanSetSystemSessionProperty(identity, propertyName));
    }

    @Override
    public void checkCanSetCatalogSessionProperty(Identity identity, String catalogName, String propertyName)
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(propertyName, "propertyName is null");

        authorizationCheck(() -> systemAccessControl.get().checkCanSetCatalogSessionProperty(identity, catalogName, propertyName));

        CatalogAccessControlEntry entry = catalogAccessControl.get(catalogName);
        if (entry != null) {
            authorizationCheck(() -> entry.getAccessControl().checkCanSetCatalogSessionProperty(identity, propertyName));
        }
    }

    @Managed
    @Nested
    public CounterStat getAuthenticationSuccess()
    {
        return authenticationSuccess;
    }

    @Managed
    @Nested
    public CounterStat getAuthenticationFail()
    {
        return authenticationFail;
    }

    @Managed
    @Nested
    public CounterStat getAuthorizationSuccess()
    {
        return authorizationSuccess;
    }

    @Managed
    @Nested
    public CounterStat getAuthorizationFail()
    {
        return authorizationFail;
    }

    private void authenticationCheck(Runnable runnable)
    {
        try {
            runnable.run();
            authenticationSuccess.update(1);
        }
        catch (PrestoException e) {
            authenticationFail.update(1);
            throw e;
        }
    }

    private void authorizationCheck(Runnable runnable)
    {
        try {
            runnable.run();
            authorizationSuccess.update(1);
        }
        catch (PrestoException e) {
            authorizationFail.update(1);
            throw e;
        }
    }

    private static Map<String, String> loadProperties(File file)
            throws Exception
    {
        requireNonNull(file, "file is null");

        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            properties.load(in);
        }
        return fromProperties(properties);
    }

    private class CatalogAccessControlEntry
    {
        private final String connectorId;
        private final ConnectorAccessControl accessControl;

        public CatalogAccessControlEntry(String connectorId, ConnectorAccessControl accessControl)
        {
            this.connectorId = requireNonNull(connectorId, "connectorId is null");
            this.accessControl = requireNonNull(accessControl, "accessControl is null");
        }

        public ConnectorAccessControl getAccessControl()
        {
            return accessControl;
        }

        public ConnectorTransactionHandle getTransactionHandle(TransactionId transactionId)
        {
            return transactionManager.getConnectorTransaction(transactionId, connectorId);
        }
    }

    private static class InitializingSystemAccessControl
            implements SystemAccessControl
    {
        @Override
        public void checkCanSetUser(Principal principal, String userName)
        {
            throw new PrestoException(SERVER_STARTING_UP, "Presto server is still initializing");
        }

        @Override
        public void checkCanSetSystemSessionProperty(Identity identity, String propertyName)
        {
            throw new PrestoException(SERVER_STARTING_UP, "Presto server is still initializing");
        }
    }

    private static class AllowAllSystemAccessControl
            implements SystemAccessControl
    {
        @Override
        public void checkCanSetUser(Principal principal, String userName)
        {
        }

        @Override
        public void checkCanSetSystemSessionProperty(Identity identity, String propertyName)
        {
        }

        @Override
        public void checkCanCreateTable(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanDropTable(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanRenameTable(Identity identity, CatalogSchemaTableName table, CatalogSchemaTableName newTable)
        {
        }

        @Override
        public void checkCanAddColumn(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanRenameColumn(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanSelectFromTable(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanInsertIntoTable(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanDeleteFromTable(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanCreateView(Identity identity, CatalogSchemaTableName view)
        {
        }

        @Override
        public void checkCanDropView(Identity identity, CatalogSchemaTableName view)
        {
        }

        @Override
        public void checkCanSelectFromView(Identity identity, CatalogSchemaTableName view)
        {
        }

        @Override
        public void checkCanCreateViewWithSelectFromTable(Identity identity, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanCreateViewWithSelectFromView(Identity identity, CatalogSchemaTableName view)
        {
        }

        @Override
        public void checkCanSetCatalogSessionProperty(Identity identity, String catalogName, String propertyName)
        {
        }

        @Override
        public void checkCanGrantTablePrivilege(Identity identity, Privilege privilege, CatalogSchemaTableName table)
        {
        }

        @Override
        public void checkCanRevokeTablePrivilege(Identity identity, Privilege privilege, CatalogSchemaTableName table)
        {
        }
    }
}
