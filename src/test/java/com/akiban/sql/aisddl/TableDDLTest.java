/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.aisddl;

import com.akiban.sql.StandardException;
import java.util.Collection;
import java.util.List;

import com.akiban.server.rowdata.RowDef;
import com.akiban.server.service.dxl.IndexCheckSummary;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.akiban.ais.model.AISBuilder;
import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.Join;
import com.akiban.ais.model.JoinColumn;
import com.akiban.ais.model.Table;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.UserTable;
import com.akiban.server.api.DDLFunctions;
import com.akiban.server.error.InvalidOperationException;
import com.akiban.server.error.DuplicateIndexException;
import com.akiban.server.error.DuplicateTableNameException;
import com.akiban.server.error.NoSuchTableException;
import com.akiban.server.service.session.Session;
import com.akiban.sql.parser.SQLParser;
import com.akiban.sql.parser.StatementNode;
import com.akiban.sql.parser.DropTableNode;
import com.akiban.sql.parser.CreateTableNode;


public class TableDDLTest {

    private static TableName dropTable;
    private static final String    DEFAULT_SCHEMA = "test";
    private static final String    DEFAULT_TABLE  = "t1";
    private static final String    JOIN_TABLE = "t2";
    private static final String    JOIN_NAME = "test/t1/test/t2";
    protected SQLParser parser;
    private DDLFunctionsMock ddlFunctions;
    private static AkibanInformationSchema ais;
    private AISBuilder builder;
    @Before
    public void before() throws Exception {
        parser = new SQLParser();
        ais = new AkibanInformationSchema();
        builder = new AISBuilder(ais);
        ddlFunctions = new DDLFunctionsMock(ais);
    }
    
    @Test
    public void createTableIfNotExistsDoesNotExist() throws StandardException
    {
        String sql = "CREATE TABLE IF NOT EXISTS desk (c1 INT)";
        createTableSimpleGenerateAIS();
        StatementNode createNode = parser.parseStatement(sql);
        assertTrue(createNode instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode) createNode);
    }

    @Test
    public void createTableIfNotExistsDoesExist() throws StandardException
    {
        String sql = "CREATE TABLE IF NOT EXISTS " + DEFAULT_TABLE + "(c1 INT)";
        createTableSimpleGenerateAIS(); // creates DEFAULT_SCHEMA.DEFAULT_TABLE
        StatementNode createNode = parser.parseStatement(sql);
        assertTrue(createNode instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode) createNode);
    }

    @Test
    public void dropExistingTableWithIfExists() throws StandardException
    {
        String sql = "DROP TABLE IF EXISTS " + DEFAULT_TABLE;
        createTableSimpleGenerateAIS();
        StatementNode node = parser.parseStatement(sql);
        assertTrue(node instanceof DropTableNode);
        TableDDL.dropTable(ddlFunctions, null, DEFAULT_SCHEMA, (DropTableNode)node);
    }
    
    @Test
    public void dropNonExistingTableWithIfExists() throws StandardException
    {
        String sql = "DROP TABLE IF EXISTS chair";
        createTableSimpleGenerateAIS();
        StatementNode node = parser.parseStatement(sql);
        assertTrue(node instanceof DropTableNode);
        TableDDL.dropTable(ddlFunctions, null, DEFAULT_SCHEMA, (DropTableNode)node);
    }
    
    @Test
    public void dropTableSimple() throws Exception {
        String sql = "DROP TABLE t1";
        dropTable = TableName.create(DEFAULT_SCHEMA, DEFAULT_TABLE);
        createTableSimpleGenerateAIS ();
        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof DropTableNode);
        
        TableDDL.dropTable(ddlFunctions, null, DEFAULT_SCHEMA, (DropTableNode)stmt);
    }

    @Test
    public void dropTableSchemaTrue() throws Exception {
        String sql = "DROP TABLE test.t1";
        dropTable = TableName.create(DEFAULT_SCHEMA, DEFAULT_TABLE);
        createTableSimpleGenerateAIS ();
        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof DropTableNode);
        
        TableDDL.dropTable(ddlFunctions, null, DEFAULT_SCHEMA, (DropTableNode)stmt);
    }

    @Test (expected=NoSuchTableException.class)
    public void dropTableSchema() throws Exception {
        String sql = "DROP TABLE foo.t1";

        createTableSimpleGenerateAIS ();

        dropTable = TableName.create("foo", DEFAULT_TABLE);

        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof DropTableNode);
        TableDDL.dropTable(ddlFunctions, null, DEFAULT_SCHEMA, (DropTableNode)stmt);
    }
    
    @Test (expected=NoSuchTableException.class)
    public void dropTableQuoted() throws Exception {
        String sql = "DROP TABLE \"T1\"";
        
        dropTable = TableName.create(DEFAULT_SCHEMA, "T1");

        createTableSimpleGenerateAIS ();
        
        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof DropTableNode);
        TableDDL.dropTable(ddlFunctions, null, DEFAULT_SCHEMA, (DropTableNode)stmt);
    }
    
    @Test
    public void createNewTable() throws StandardException
    {
        String sql = "CREATE TABLE desk (c1 INT)";
        createTableSimpleGenerateAIS();
        
        StatementNode createNode = parser.parseStatement(sql);
        assertTrue(createNode instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode)createNode);
        
    }

    /*
    create table t1 (col1 int primary key)
    CREATE TABLE "MyMixedCaseTable" ("Col1" int primary key)
    create table test.t1 (col1 integer, primary key (col1))
    CREATE TABLE t1 (col1 int primary key, foreign key (col1) references t2 (col2))
    create table t1 (col1 integer, 
    primary key (col1), 
    grouping foreign key (col1) references t2 (col1))
    */    
    @Test
    public void createTableSimple() throws Exception {
        String sql = "CREATE TABLE d0 (c1 INT)";
        
        createTableSimpleGenerateAIS();

        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode)stmt);
        
    }
    
    @Test
    public void createTablePK() throws Exception {
        String sql = "CREATE TABLE d1 (c1 INT NOT NULL PRIMARY KEY)";

        createTablePKGenerateAIS();
        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode)stmt);
    }
    
    @Test
    public void createTableUniqueKey() throws Exception {
        String sql = "CREATE TABLE  d2 (C1 int NOT NULL UNIQUE)";
        createTableUniqueKeyGenerateAIS();
        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode)stmt);
    }

    @Test (expected=DuplicateIndexException.class)
    public void createTable2PKs() throws Exception {
        String sql = "CREATE TABLE test.d3 (c1 int primary key, c2 int NOT NULL, primary key (c2))";
        
        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode)stmt);
    }
    
    @Test
    public void createTableFKSimple() throws Exception {
        String sql = "CREATE TABLE if not exists test.t2 (c1 int not null primary key, c2 int not null, grouping foreign key (c2) references t1)";
        createTableFKSimpleGenerateAIS();
        StatementNode stmt = parser.parseStatement(sql);
        assertTrue (stmt instanceof CreateTableNode);
        TableDDL.createTable(ddlFunctions, null, DEFAULT_SCHEMA, (CreateTableNode)stmt);
    }
    
    public static class DDLFunctionsMock implements DDLFunctions {
        private AkibanInformationSchema internalAIS = null;
        public DDLFunctionsMock() {}
        
        public DDLFunctionsMock(AkibanInformationSchema ais) { this.internalAIS = ais; }
        
        @Override
        public void createTable(Session session, UserTable table) {

            for (Column col : table.getColumnsIncludingInternal()) {
                assertNotNull (col.getName());
                assertNotNull (ais.getUserTable(dropTable));
                assertNotNull (ais.getUserTable(dropTable).getColumn(col.getName()));
                assertEquals (col.getNullable(), ais.getUserTable(dropTable).getColumn(col.getName()).getNullable());
            }
            for (Column col : ais.getTable(dropTable).getColumnsIncludingInternal()) {
                assertNotNull (col.getName());
                assertNotNull (table.getColumn(col.getName()));
            }
            
            checkIndexes (table, ais.getUserTable(dropTable));
            checkIndexes (ais.getUserTable(dropTable), table);
            
            if (table.getParentJoin() != null) {
                checkJoin (table.getParentJoin(), ais.getJoin(JOIN_NAME));
            }
            
        }

        @Override
        public void renameTable(Session session, TableName currentName, TableName newName)
        {
            throw new UnsupportedOperationException();
        }

        private void checkIndexes(UserTable sourceTable, UserTable checkTable) {
            for (Index index : sourceTable.getIndexesIncludingInternal()) {
                assertNotNull(checkTable.getIndexIncludingInternal(index.getIndexName().getName()));
                Index checkIndex = checkTable.getIndexIncludingInternal(index.getIndexName().getName());
                for (IndexColumn col : index.getKeyColumns()) {
                    checkIndex.getKeyColumns().get(col.getPosition());
                }
            }
        }
        private void checkJoin (Join sourceJoin, Join checkJoin) {
            assertEquals (sourceJoin.getName(), checkJoin.getName()); 
            assertEquals (sourceJoin.getJoinColumns().size(), checkJoin.getJoinColumns().size());
            for (int i = 0; i < sourceJoin.getJoinColumns().size(); i++) {
                JoinColumn sourceColumn = sourceJoin.getJoinColumns().get(i);
                JoinColumn checkColumn = checkJoin.getJoinColumns().get(i);
                
                assertEquals (sourceColumn.getChild().getName(), checkColumn.getChild().getName());
                assertEquals (sourceColumn.getParent().getName(), checkColumn.getParent().getName());
            }
        }

        @Override
        public void dropTable(Session session, TableName tableName) {
            assertEquals(tableName, dropTable);
        }
        
        @Override
        public AkibanInformationSchema getAIS(Session session) {
            return internalAIS;
        }

        @Override
        public void createIndexes(Session session,
                Collection<? extends Index> indexesToAdd) {}

        @Override
        public void dropGroup(Session session, String groupName) {}

        @Override
        public void dropGroupIndexes(Session session, String groupName,
                Collection<String> indexesToDrop) {}

        @Override
        public void dropSchema(Session session, String schemaName) {}

        @Override
        public void dropTableIndexes(Session session, TableName tableName,
                Collection<String> indexesToDrop) {}

        @Override
        public List<String> getDDLs(Session session)
                throws InvalidOperationException {
            return null;
        }

        @Override
        public int getGeneration() {
            return 0;
        }

        @Override
        public long getTimestamp() {
            return 0;
        }

        @Override
        public RowDef getRowDef(int tableId) {
            return null;
        }

        @Override
        public Table getTable(Session session, int tableId) {
            return null;
        }

        @Override
        public Table getTable(Session session, TableName tableName) {
            return null;
        }

        @Override
        public int getTableId(Session session, TableName tableName) {
            return 0;
        }

        @Override
        public TableName getTableName(Session session, int tableId) {
            return null;
        }

        @Override
        public UserTable getUserTable(Session session, TableName tableName) {
            return null;
        }

        @Override
        public void updateTableStatistics(Session session, TableName tableName, Collection<String> indexesToUpdate) {}

        @Override
        public IndexCheckSummary checkAndFixIndexes(Session session, String schemaRegex, String tableRegex) {
            return null;
        }
    } // END class DDLFunctionsMock

    /*"CREATE TABLE t1 (c1 INT)";*/
    private void createTableSimpleGenerateAIS () {
        dropTable = TableName.create(DEFAULT_SCHEMA, DEFAULT_TABLE);
        
        builder.userTable(DEFAULT_SCHEMA, DEFAULT_TABLE);
        builder.column(DEFAULT_SCHEMA, DEFAULT_TABLE, "c1", 0, "int", Long.valueOf(0), Long.valueOf(0), true, false, null, null);
        builder.basicSchemaIsComplete();
    }
    
    /*CREATE TABLE t1 (c1 INT NOT NULL PRIMARY KEY)*/
    private void createTablePKGenerateAIS() {
        dropTable = TableName.create(DEFAULT_SCHEMA, DEFAULT_TABLE);
        
        builder.userTable(DEFAULT_SCHEMA, DEFAULT_TABLE);
        builder.column(DEFAULT_SCHEMA, DEFAULT_TABLE, "c1", 0, "int", (long)0, (long)0, false, false, null, null);
        builder.index(DEFAULT_SCHEMA, DEFAULT_TABLE, "PRIMARY", true, Index.PRIMARY_KEY_CONSTRAINT);
        builder.indexColumn(DEFAULT_SCHEMA, DEFAULT_TABLE, "PRIMARY", "c1", 0, true, 0);
        builder.basicSchemaIsComplete();
    }

    /*CREATE TABLE t1 (C1 int NOT NULL UNIQUE) */
    private void createTableUniqueKeyGenerateAIS() {
        dropTable = TableName.create(DEFAULT_SCHEMA, DEFAULT_TABLE);
        
        builder.userTable(DEFAULT_SCHEMA, DEFAULT_TABLE);
        builder.column(DEFAULT_SCHEMA, DEFAULT_TABLE, "c1", 0, "int", (long)0, (long)0, false, false, null, null);
        builder.index(DEFAULT_SCHEMA, DEFAULT_TABLE, "c1", true, Index.UNIQUE_KEY_CONSTRAINT);
        builder.indexColumn(DEFAULT_SCHEMA, DEFAULT_TABLE, "c1", "c1", 0, true, 0);
        builder.basicSchemaIsComplete();
    }

    /* CREATE TABLE t1 (c1 int not null primary key) */
    /* CREATE TABLE t2 (c1 int not null primary key, c2 int not null, grouping foreign key (c2) references t1) */
    private void createTableFKSimpleGenerateAIS() {
        dropTable = TableName.create(DEFAULT_SCHEMA, JOIN_TABLE);

        // Re-gen the DDLFunctions to have the AIS for internal references. 
        ddlFunctions = new DDLFunctionsMock(ais);
        // table t1:
        builder.userTable(DEFAULT_SCHEMA, DEFAULT_TABLE);
        builder.column(DEFAULT_SCHEMA, DEFAULT_TABLE, "c1", 0, "int", (long)0, (long)0, false, false, null, null);
        builder.column(DEFAULT_SCHEMA, DEFAULT_TABLE, "c2", 1, "int", (long)0, (long)0, false, false, null, null);
        builder.index(DEFAULT_SCHEMA, DEFAULT_TABLE, "pk", true, Index.PRIMARY_KEY_CONSTRAINT);
        builder.indexColumn(DEFAULT_SCHEMA, DEFAULT_TABLE, "pk", "c1", 0, true, 0);
        builder.basicSchemaIsComplete();
        builder.createGroup("t1", DEFAULT_SCHEMA, "_akiban_t1");
        builder.addTableToGroup("t1", DEFAULT_SCHEMA, DEFAULT_TABLE);
        builder.groupingIsComplete();
        
        // table t2:
        builder.userTable(DEFAULT_SCHEMA, JOIN_TABLE);
        builder.column(DEFAULT_SCHEMA, JOIN_TABLE, "c1", 0, "int", (long)0, (long)0, false, false, null, null);
        builder.column(DEFAULT_SCHEMA, JOIN_TABLE, "c2", 1, "int", (long)0, (long)0, false, false, null, null);
        builder.index(DEFAULT_SCHEMA, JOIN_TABLE, "PRIMARY", true, Index.PRIMARY_KEY_CONSTRAINT);
        builder.indexColumn(DEFAULT_SCHEMA, JOIN_TABLE, "PRIMARY", "c1", 0, true, 0);
        builder.basicSchemaIsComplete();
        // do the join
        builder.joinTables(JOIN_NAME, DEFAULT_SCHEMA, DEFAULT_TABLE, DEFAULT_SCHEMA, JOIN_TABLE);
        builder.joinColumns(JOIN_NAME, DEFAULT_SCHEMA, DEFAULT_TABLE, "c1", DEFAULT_SCHEMA, JOIN_TABLE, "c2");
        
        builder.addJoinToGroup("t1", JOIN_NAME, 0);
        builder.groupingIsComplete();
    }
}
