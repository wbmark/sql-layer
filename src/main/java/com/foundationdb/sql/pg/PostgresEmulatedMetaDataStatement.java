/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.pg;

import com.foundationdb.ais.model.*;
import com.foundationdb.qp.operator.QueryBindings;
import com.foundationdb.server.types.AkType;
import com.foundationdb.server.types3.aksql.aktypes.AkBool;
import com.foundationdb.server.types3.mcompat.mtypes.MNumeric;
import com.foundationdb.server.types3.mcompat.mtypes.MString;
import com.foundationdb.sql.optimizer.plan.CostEstimate;
import com.foundationdb.sql.parser.ParameterNode;
import com.foundationdb.sql.parser.StatementNode;
import com.foundationdb.sql.server.ServerValueEncoder;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.regex.*;

/**
 * Canned handling for fixed SQL text that comes from tools that
 * believe they are talking to a real Postgres database.
 */
public class PostgresEmulatedMetaDataStatement implements PostgresStatement
{
    enum Query {
        // ODBC driver sends this at the start; returning no rows is fine (and normal).
        ODBC_LO_TYPE_QUERY("select oid, typbasetype from pg_type where typname = 'lo'"),
        // SEQUEL 3.33.0 (http://sequel.rubyforge.org/) sends this when opening a new connection
        SEQUEL_B_TYPE_QUERY("select oid, typname from pg_type where typtype = 'b'"),
        // Npgsql (http://npgsql.projects.postgresql.org/) sends this at startup.
        NPGSQL_TYPE_QUERY("SELECT typname, oid FROM pg_type WHERE typname IN \\((.+)\\)", true),
        // PSQL \dn
        PSQL_LIST_SCHEMAS("SELECT n.nspname AS \"Name\",\\s*" +
                          "(?:pg_catalog.pg_get_userbyid\\(n.nspowner\\)|u.usename) AS \"Owner\"\\s+" +
                          "FROM pg_catalog.pg_namespace n\\s+" +
                          "(?:LEFT JOIN pg_catalog.pg_user u\\s+" +
                          "ON n.nspowner=u.usesysid\\s+)?" +
                          "(?:WHERE\\s+)?" +
                          "(?:\\(n.nspname !~ '\\^pg_temp_' OR\\s+" + 
                          "n.nspname = \\(pg_catalog.current_schemas\\(true\\)\\)\\[1\\]\\)\\s+)?" +
                          "(n.nspname !~ '\\^pg_' AND n.nspname <> 'information_schema'\\s+)?" + // 1
                          "(?:AND\\s+)?" + 
                          "(n.nspname ~ '(.+)'\\s+)?" + // 2 (3)
                          "ORDER BY 1;?", true),
        // PSQL \d, \dt, \dv
        PSQL_LIST_TABLES("SELECT n.nspname as \"Schema\",\\s*" +
                         "c.relname as \"Name\",\\s*" +
                         "CASE c.relkind WHEN 'r' THEN 'table' WHEN 'v' THEN 'view' WHEN 'i' THEN 'index' WHEN 'S' THEN 'sequence' WHEN 's' THEN 'special' (?:WHEN 'f' THEN 'foreign table' )?END as \"Type\",\\s+" +
                         "(?:pg_catalog.pg_get_userbyid\\(c.relowner\\)|u.usename|r.rolname) as \"Owner\"\\s+" +
                         "FROM pg_catalog.pg_class c\\s+" +
                         "(?:LEFT JOIN pg_catalog.pg_user u ON u.usesysid = c.relowner\\s+)?" +
                         "(?:JOIN pg_catalog.pg_roles r ON r.oid = c.relowner\\s+)?" +
                         "LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace\\s+" +
                         "WHERE c.relkind IN \\((.+)\\)\\s+" + // 1
                         "(AND n.nspname <> 'pg_catalog'\\s+" +
                         "AND n.nspname <> 'information_schema'\\s+)?" + // 2
                         "(?:AND n.nspname !~ '\\^pg_toast'\\s+)?" +
                         "(?:(AND n.nspname NOT IN \\('pg_catalog', 'pg_toast'\\)\\s+)|" + // 3
                         "(AND n.nspname = 'pg_catalog')\\s+)?" + // 4
                         "(AND c.relname ~ '(.+)'\\s+)?" + // 5 (6)
                         "(AND n.nspname ~ '(.+)'\\s+)?" + // 7 (8)
                         "(?:AND pg_catalog.pg_table_is_visible\\(c.oid\\)\\s+)?" +
                         "(AND c.relname ~ '(.+)'\\s+)?" + // 9 (10)
                         "ORDER BY 1,2;?", true),
        // PSQL \d NAME
        PSQL_DESCRIBE_TABLES_1("SELECT c.oid,\\s*" +
                               "n.nspname,\\s*" +
                               "c.relname\\s+" +
                               "FROM pg_catalog.pg_class c\\s+" +
                               "LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace\\s+" +
                               "WHERE " +
                               "(?:pg_catalog.pg_table_is_visible\\(c.oid\\)\\s+AND )?" +
                               "(n.nspname ~ '(.+)'\\s+)?" + // 1 (2)
                               "((?:AND )?c.relname ~ '(.+)'\\s+)?" + // 3 (4)
                               "((?:AND )?n.nspname ~ '(.+)'\\s+)?" + // 5 (6)
                               "(?:AND pg_catalog.pg_table_is_visible\\(c.oid\\)\\s+)?" +
                               "ORDER BY 2, 3;?", true),
        PSQL_DESCRIBE_TABLES_2("SELECT c.relchecks, c.relkind, c.relhasindex, c.relhasrules, c.relhastriggers, c.relhasoids, '', c.reltablespace\\s+" +
                               "FROM pg_catalog.pg_class c\\s+" +
                               "LEFT JOIN pg_catalog.pg_class tc ON \\(c.reltoastrelid = tc.oid\\)\\s+" +
                               "WHERE c.oid = '(-?\\d+)';?\\s*", true), // 1
        PSQL_DESCRIBE_TABLES_2X("SELECT relhasindex, relkind, relchecks, reltriggers, relhasrules(,\\s*relhasoids , reltablespace)?\\s+" + // 1
                               "FROM pg_catalog.pg_class\\s+" +
                               "WHERE oid = '(-?\\d+)';?\\s*", true), // 2
        PSQL_DESCRIBE_TABLES_3("SELECT a.attname,\\s*" +
                               "pg_catalog.format_type\\(a.atttypid, a.atttypmod\\),\\s*" +
                               "\\(SELECT substring\\((?:pg_catalog.pg_get_expr\\(d.adbin, d.adrelid\\)|d.adsrc) for 128\\)\\s*" +
                               "FROM pg_catalog.pg_attrdef d\\s+" +
                               "WHERE d.adrelid = a.attrelid AND d.adnum = a.attnum AND a.atthasdef\\),\\s*" +
                               "a.attnotnull, a.attnum,?\\s*" +
                               "(NULL AS attcollation\\s+)?" + // 1
                               "FROM pg_catalog.pg_attribute a\\s+" +
                               "WHERE a.attrelid = '(-?\\d+)' AND a.attnum > 0 AND NOT a.attisdropped\\s+" + // 2
                               "ORDER BY a.attnum;?", true),
        PSQL_DESCRIBE_TABLES_4A("SELECT c.oid::pg_catalog.regclass FROM pg_catalog.pg_class c, pg_catalog.pg_inherits i WHERE c.oid=i.inhparent AND i.inhrelid = '(-?\\d+)' ORDER BY inhseqno;?", true),
        PSQL_DESCRIBE_TABLES_4B("SELECT c.oid::pg_catalog.regclass FROM pg_catalog.pg_class c, pg_catalog.pg_inherits i WHERE c.oid=i.inhrelid AND i.inhparent = '(-?\\d+)' ORDER BY c.oid::pg_catalog.regclass::pg_catalog.text;?", true),
        PSQL_DESCRIBE_TABLES_5("SELECT c.relname FROM pg_catalog.pg_class c, pg_catalog.pg_inherits i WHERE c.oid=i.inhparent AND i.inhrelid = '(-?\\d+)' ORDER BY inhseqno ASC;?", true),
        PSQL_DESCRIBE_INDEXES("SELECT c2.relname, i.indisprimary, i.indisunique(, i.indisclustered)?(, i.indisvalid)?, pg_catalog.pg_get_indexdef\\(i.indexrelid(?:, 0, true)?\\),?\\s*" + // 1, 2
                              "(null AS constraintdef, null AS contype, false AS condeferrable, false AS condeferred, )?(?:c2.reltablespace)?\\s+" + // 3
                              "FROM pg_catalog.pg_class c, pg_catalog.pg_class c2, pg_catalog.pg_index i\\s+" +
                              "WHERE c.oid = '(-?\\d+)' AND c.oid = i.indrelid AND i.indexrelid = c2.oid\\s+" + // 4
                              "ORDER BY i.indisprimary DESC, i.indisunique DESC, c2.relname;?", true),
        PSQL_DESCRIBE_FOREIGN_KEYS_1("SELECT conname,\\s*" +
                                     "pg_catalog.pg_get_constraintdef\\((?:r.oid, true|oid, true|oid)\\) as condef\\s+" +
                                     "FROM pg_catalog.pg_constraint r\\s+" +
                                     "WHERE r.conrelid = '(-?\\d+)' AND r.contype = 'f'(?: ORDER BY 1)?;?", true),
        PSQL_DESCRIBE_FOREIGN_KEYS_2("SELECT conname, conrelid::pg_catalog.regclass,\\s*" +
                                    "pg_catalog.pg_get_constraintdef\\(c.oid, true\\) as condef\\s+" +
                                    "FROM pg_catalog.pg_constraint c\\s+" +
                                    "WHERE c.confrelid = '(-?\\d+)' AND c.contype = 'f' ORDER BY 1;?", true),
        PSQL_DESCRIBE_TRIGGERS("SELECT t.tgname, pg_catalog.pg_get_triggerdef\\(t.oid\\)(, t.tgenabled)?\\s+" + // 1
                               "FROM pg_catalog.pg_trigger t\\s+" +
                               "WHERE t.tgrelid = '(-?\\d+)' AND (?:t.tgconstraint = 0|" + // 2
                               "\\(not tgisconstraint  OR NOT EXISTS  \\(SELECT 1 FROM pg_catalog.pg_depend d    JOIN pg_catalog.pg_constraint c ON \\(d.refclassid = c.tableoid AND d.refobjid = c.oid\\)    WHERE d.classid = t.tableoid AND d.objid = t.oid AND d.deptype = 'i' AND c.contype = 'f'\\)\\))(?:\\s+ORDER BY 1)?;?", true),
        PSQL_DESCRIBE_VIEW("SELECT pg_catalog.pg_get_viewdef\\('(-?\\d+)'::pg_catalog.oid, true\\);?", true),
        CHARTIO_TABLES("SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM, c.relname AS TABLE_NAME,  CASE n.nspname ~ '^pg_' OR n.nspname = 'information_schema'  WHEN true THEN CASE  WHEN n.nspname = 'pg_catalog' OR n.nspname = 'information_schema' THEN CASE c.relkind   WHEN 'r' THEN 'SYSTEM TABLE'   WHEN 'v' THEN 'SYSTEM VIEW'   WHEN 'i' THEN 'SYSTEM INDEX'   ELSE NULL   END  WHEN n.nspname = 'pg_toast' THEN CASE c.relkind   WHEN 'r' THEN 'SYSTEM TOAST TABLE'   WHEN 'i' THEN 'SYSTEM TOAST INDEX'   ELSE NULL   END  ELSE CASE c.relkind   WHEN 'r' THEN 'TEMPORARY TABLE'   WHEN 'i' THEN 'TEMPORARY INDEX'   WHEN 'S' THEN 'TEMPORARY SEQUENCE'   WHEN 'v' THEN 'TEMPORARY VIEW'   ELSE NULL   END  END  WHEN false THEN CASE c.relkind  WHEN 'r' THEN 'TABLE'  WHEN 'i' THEN 'INDEX'  WHEN 'S' THEN 'SEQUENCE'  WHEN 'v' THEN 'VIEW'  WHEN 'c' THEN 'TYPE'  WHEN 'f' THEN 'FOREIGN TABLE'  ELSE NULL  END  ELSE NULL  END  AS TABLE_TYPE, d.description AS REMARKS  FROM pg_catalog.pg_namespace n, pg_catalog.pg_class c  LEFT JOIN pg_catalog.pg_description d ON (c.oid = d.objoid AND d.objsubid = 0)  LEFT JOIN pg_catalog.pg_class dc ON (d.classoid=dc.oid AND dc.relname='pg_class')  LEFT JOIN pg_catalog.pg_namespace dn ON (dn.oid=dc.relnamespace AND dn.nspname='pg_catalog')  WHERE c.relnamespace = n.oid  AND (false  OR ( c.relkind = 'r' AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' )  OR ( c.relkind = 'v' AND n.nspname <> 'pg_catalog' AND n.nspname <> 'information_schema' ) )  ORDER BY TABLE_TYPE,TABLE_SCHEM,TABLE_NAME "),
        CHARTIO_PRIMARY_KEYS("SELECT NULL AS TABLE_CAT, n.nspname AS TABLE_SCHEM,   ct.relname AS TABLE_NAME, a.attname AS COLUMN_NAME,   \\(i.keys\\).n AS KEY_SEQ, ci.relname AS PK_NAME FROM pg_catalog.pg_class ct   JOIN pg_catalog.pg_attribute a ON \\(ct.oid = a.attrelid\\)   JOIN pg_catalog.pg_namespace n ON \\(ct.relnamespace = n.oid\\)   JOIN \\(SELECT i.indexrelid, i.indrelid, i.indisprimary,              information_schema._pg_expandarray\\(i.indkey\\) AS keys         FROM pg_catalog.pg_index i\\) i     ON \\(a.attnum = \\(i.keys\\).x AND a.attrelid = i.indrelid\\)   JOIN pg_catalog.pg_class ci ON \\(ci.oid = i.indexrelid\\) WHERE true  AND ct.relname = E'(.+)' AND i.indisprimary  ORDER BY table_name, pk_name, key_seq", true),
        CHARTIO_FOREIGN_KEYS("SELECT NULL::text AS PKTABLE_CAT, pkn.nspname AS PKTABLE_SCHEM, pkc.relname AS PKTABLE_NAME, pka.attname AS PKCOLUMN_NAME, NULL::text AS FKTABLE_CAT, fkn.nspname AS FKTABLE_SCHEM, fkc.relname AS FKTABLE_NAME, fka.attname AS FKCOLUMN_NAME, pos.n AS KEY_SEQ, CASE con.confupdtype  WHEN 'c' THEN 0 WHEN 'n' THEN 2 WHEN 'd' THEN 4 WHEN 'r' THEN 1 WHEN 'a' THEN 3 ELSE NULL END AS UPDATE_RULE, CASE con.confdeltype  WHEN 'c' THEN 0 WHEN 'n' THEN 2 WHEN 'd' THEN 4 WHEN 'r' THEN 1 WHEN 'a' THEN 3 ELSE NULL END AS DELETE_RULE, con.conname AS FK_NAME, pkic.relname AS PK_NAME, CASE  WHEN con.condeferrable AND con.condeferred THEN 5 WHEN con.condeferrable THEN 6 ELSE 7 END AS DEFERRABILITY  FROM  pg_catalog.pg_namespace pkn, pg_catalog.pg_class pkc, pg_catalog.pg_attribute pka,  pg_catalog.pg_namespace fkn, pg_catalog.pg_class fkc, pg_catalog.pg_attribute fka,  pg_catalog.pg_constraint con,  pg_catalog.generate_series\\(1, 8\\) pos\\(n\\),  pg_catalog.pg_depend dep, pg_catalog.pg_class pkic  WHERE pkn.oid = pkc.relnamespace AND pkc.oid = pka.attrelid AND pka.attnum = con.confkey\\[pos.n\\] AND con.confrelid = pkc.oid  AND fkn.oid = fkc.relnamespace AND fkc.oid = fka.attrelid AND fka.attnum = con.conkey\\[pos.n\\] AND con.conrelid = fkc.oid  AND con.contype = 'f' AND con.oid = dep.objid AND pkic.oid = dep.refobjid AND pkic.relkind = 'i' AND dep.classid = 'pg_constraint'::regclass::oid AND dep.refclassid = 'pg_class'::regclass::oid  AND fkc.relname = E'(.+)' ORDER BY pkn.nspname,pkc.relname,pos.n", true),
        CHARTIO_COLUMNS("SELECT \\* FROM \\(SELECT n.nspname,c.relname,a.attname,a.atttypid,a.attnotnull OR \\(t.typtype = 'd' AND t.typnotnull\\) AS attnotnull,a.atttypmod,a.attlen,row_number\\(\\) OVER \\(PARTITION BY a.attrelid ORDER BY a.attnum\\) AS attnum, pg_catalog.pg_get_expr\\(def.adbin, def.adrelid\\) AS adsrc,dsc.description,t.typbasetype,t.typtype  FROM pg_catalog.pg_namespace n  JOIN pg_catalog.pg_class c ON \\(c.relnamespace = n.oid\\)  JOIN pg_catalog.pg_attribute a ON \\(a.attrelid=c.oid\\)  JOIN pg_catalog.pg_type t ON \\(a.atttypid = t.oid\\)  LEFT JOIN pg_catalog.pg_attrdef def ON \\(a.attrelid=def.adrelid AND a.attnum = def.adnum\\)  LEFT JOIN pg_catalog.pg_description dsc ON \\(c.oid=dsc.objoid AND a.attnum = dsc.objsubid\\)  LEFT JOIN pg_catalog.pg_class dc ON \\(dc.oid=dsc.classoid AND dc.relname='pg_class'\\)  LEFT JOIN pg_catalog.pg_namespace dn ON \\(dc.relnamespace=dn.oid AND dn.nspname='pg_catalog'\\)  WHERE a.attnum > 0 AND NOT a.attisdropped  AND c.relname LIKE E'(.+)'\\) c WHERE true  ORDER BY nspname,c.relname,attnum ", true),
        CHARTIO_MAX_KEYS_SETTING("SELECT setting FROM pg_catalog.pg_settings WHERE name='max_index_keys'"),
        CLSQL_LIST_OBJECTS("SELECT relname FROM pg_class WHERE \\(relkind =\n'(\\w)'\\)" + // 1
                           "(?: AND \\(relowner=\\(SELECT usesysid FROM pg_user WHERE \\(usename='(.+)'\\)\\)\\))?" + // 2
                           "( AND \\(relowner<>\\(SELECT usesysid FROM pg_user WHERE usename='postgres'\\)\\))?", true), // 3
        CLSQL_LIST_ATTRIBUTES("SELECT attname FROM pg_class,pg_attribute WHERE pg_class.oid=attrelid AND attisdropped = FALSE AND relname='(.+)'" + // 1
                              "(?: AND \\(relowner=\\(SELECT usesysid FROM pg_user WHERE usename='(.+)'\\)\\))?" + // 2
                              "( AND \\(not \\(relowner=1\\)\\))?", true), // 3
        CLSQL_ATTRIBUTE_TYPE("SELECT pg_type.typname,pg_attribute.attlen,pg_attribute.atttypmod,pg_attribute.attnotnull FROM pg_type,pg_class,pg_attribute WHERE pg_class.oid=pg_attribute.attrelid AND pg_class.relname='(.+)' AND pg_attribute.attname='(.+)' AND pg_attribute.atttypid=pg_type.oid" + // 1 2
                           "(?: AND \\(relowner=\\(SELECT usesysid FROM pg_user WHERE \\(usename='(.+)'\\)\\)\\))?" + // 3
                           "( AND \\(relowner<>\\(SELECT usesysid FROM pg_user WHERE usename='postgres'\\)\\))?", true), // 4
        POSTMODERN_LIST("\\(SELECT relname FROM pg_catalog.pg_class INNER JOIN pg_catalog.pg_namespace ON \\(relnamespace = pg_namespace.oid\\) WHERE \\(\\(relkind = E?'(\\w)'\\) and \\(nspname NOT IN \\(E?'pg_catalog', E?'pg_toast'\\)\\) and pg_catalog.pg_table_is_visible\\(pg_class.oid\\)\\)\\)", true),
        POSTMODERN_EXISTS("\\(SELECT \\(EXISTS \\(SELECT 1 FROM pg_catalog.pg_class WHERE \\(\\(relkind = E?'(\\w)'\\) and \\(relname = E?'(.+)'\\)\\)\\)\\)\\)", true),
        POSTMODERN_TABLE_DESCRIPTION("\\(\\(SELECT DISTINCT attname, typname, \\(not attnotnull\\), attnum FROM pg_catalog.pg_attribute INNER JOIN pg_catalog.pg_type ON \\(pg_type.oid = atttypid\\) INNER JOIN pg_catalog.pg_class ON \\(\\(pg_class.oid = attrelid\\) and \\(pg_class.relname = E?'(.+)'\\)\\) INNER JOIN pg_catalog.pg_namespace ON \\(pg_namespace.oid = pg_class.relnamespace\\) WHERE \\(\\(attnum > 0\\) and (?:true|\\(pg_namespace.nspname = E?'(.+)'\\))\\)\\) ORDER BY attnum\\)", true);

        private String sql;
        private Pattern pattern;

        Query(String sql) {
            this.sql = sql;
        }

        Query(String str, boolean regexp) {
            if (regexp) {
                pattern = Pattern.compile(str);
            }
            else {
                sql = str;
            }
        }

        public boolean matches(String sql, List<String> groups) {
            if (pattern == null) {
                if (sql.equalsIgnoreCase(this.sql)) {
                    groups.add(sql);
                    return true;
                }
            }
            else {
                Matcher matcher = pattern.matcher(sql);
                if (matcher.matches()) {
                    for (int i = 0; i <= matcher.groupCount(); i++) {
                        groups.add(matcher.group(i));
                    }
                    return true;
                }
            }
            return false;
        }
    }

    static final boolean LIST_TABLES_BY_GROUP = true;

    private Query query;
    private List<String> groups;
    //private boolean usePVals;
    private long aisGeneration;

    protected PostgresEmulatedMetaDataStatement(Query query, List<String> groups) {
        this.query = query;
        this.groups = groups;
    }

    private static final boolean FIELDS_NULLABLE = true;

    static final PostgresType BOOL_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.BOOL_TYPE_OID, (short)1, -1, AkType.BOOL, AkBool.INSTANCE.instance(FIELDS_NULLABLE));
    static final PostgresType INT2_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.INT2_TYPE_OID, (short)2, -1, AkType.LONG, MNumeric.SMALLINT.instance(FIELDS_NULLABLE));
    static final PostgresType INT4_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.INT4_TYPE_OID, (short)4, -1, AkType.LONG, MNumeric.INT.instance(FIELDS_NULLABLE));
    static final PostgresType OID_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.OID_TYPE_OID, (short)4, -1, AkType.LONG, MNumeric.INT.instance(FIELDS_NULLABLE));
    static final PostgresType TYPNAME_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)255, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType IDENT_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)128, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType LIST_TYPE_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)13, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType CHAR0_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)0, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType CHAR1_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)1, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType DEFVAL_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)128, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType INDEXDEF_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)1024, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType CONDEF_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)512, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType VIEWDEF_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)32768, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));
    static final PostgresType PATH_PG_TYPE = 
        new PostgresType(PostgresType.TypeOid.NAME_TYPE_OID, (short)1024, -1, AkType.VARCHAR, MString.VARCHAR.instance(FIELDS_NULLABLE));

    @Override
    public PostgresType[] getParameterTypes() {
        return null;
    }

    @Override
    public void sendDescription(PostgresQueryContext context,
                                boolean always, boolean params)
            throws IOException {
        int ncols;
        String[] names;
        PostgresType[] types;
        switch (query) {
        case ODBC_LO_TYPE_QUERY:
            ncols = 2;
            names = new String[] { "oid", "typbasetype" };
            types = new PostgresType[] { OID_PG_TYPE, OID_PG_TYPE };
            break;
        case SEQUEL_B_TYPE_QUERY:
            ncols = 2;
            names = new String[] { "oid", "typname" };
            types = new PostgresType[] { OID_PG_TYPE, TYPNAME_PG_TYPE };
            break;
        case NPGSQL_TYPE_QUERY:
            ncols = 2;
            names = new String[] { "typname", "oid" };
            types = new PostgresType[] { TYPNAME_PG_TYPE, OID_PG_TYPE };
            break;
        case PSQL_LIST_SCHEMAS:
            ncols = 2;
            names = new String[] { "Name", "Owner" };
            types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE };
            break;
        case PSQL_LIST_TABLES:
            ncols = 4;
            if (LIST_TABLES_BY_GROUP) {
                names = new String[] { "Schema", "Name", "Type", "Path" };
                types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, LIST_TYPE_PG_TYPE, PATH_PG_TYPE };
            }
            else {
                names = new String[] { "Schema", "Name", "Type", "Owner" };
                types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, LIST_TYPE_PG_TYPE, IDENT_PG_TYPE };
            }
            break;
        case PSQL_DESCRIBE_TABLES_1:
            ncols = 3;
            names = new String[] { "oid", "nspname", "relname" };
            types = new PostgresType[] { OID_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE };
            break;
        case PSQL_DESCRIBE_TABLES_2:
            ncols = 8;
            names = new String[] { "relchecks", "relkind",  "relhasindex", "relhasrules", "relhastriggers", "relhasoids", "?column?", "reltablespace" };
            types = new PostgresType[] { INT2_PG_TYPE, CHAR1_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, CHAR0_PG_TYPE, OID_PG_TYPE };
            break;
        case PSQL_DESCRIBE_TABLES_2X:
            ncols = (groups.get(1) != null) ? 7 : 5;
            names = new String[] { "relhasindex", "relkind", "relchecks", "reltriggers", "relhasrules", "relhasoids", "reltablespace" };
            types = new PostgresType[] { BOOL_PG_TYPE, CHAR1_PG_TYPE, INT2_PG_TYPE, INT2_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, OID_PG_TYPE };
            break;
        case PSQL_DESCRIBE_TABLES_3:
            ncols = (groups.get(1) != null) ? 6 : 5;
            names = new String[] { "attname", "format_type", "?column?", "attnotnull", "attnum", "attcollation" };
            types = new PostgresType[] { IDENT_PG_TYPE, TYPNAME_PG_TYPE, DEFVAL_PG_TYPE, BOOL_PG_TYPE, INT2_PG_TYPE, IDENT_PG_TYPE };
            break;
        case PSQL_DESCRIBE_TABLES_4A:
        case PSQL_DESCRIBE_TABLES_4B:
            ncols = 1;
            names = new String[] { "oid" };
            types = new PostgresType[] { OID_PG_TYPE };
            break;
        case PSQL_DESCRIBE_TABLES_5:
            ncols = 1;
            names = new String[] { "relname" };
            types = new PostgresType[] { IDENT_PG_TYPE };
            break;
        case PSQL_DESCRIBE_INDEXES:
            if (groups.get(1) == null) {
                ncols = 4;
                names = new String[] { "relname", "indisprimary", "indisunique", "pg_get_indexdef" };
                types = new PostgresType[] { IDENT_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, INDEXDEF_PG_TYPE };
            }
            else if (groups.get(2) == null) {
                ncols = 6;
                names = new String[] { "relname", "indisprimary", "indisunique", "indisclustered", "pg_get_indexdef", "reltablespace" };
                types = new PostgresType[] { IDENT_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, INDEXDEF_PG_TYPE, INT2_PG_TYPE };
            }
            else if (groups.get(3) == null) {
                ncols = 7;
                names = new String[] { "relname", "indisprimary", "indisunique", "indisclustered", "indisvalid", "pg_get_indexdef", "reltablespace" };
                types = new PostgresType[] { IDENT_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, INDEXDEF_PG_TYPE, INT2_PG_TYPE };
            }
            else {
                ncols = 11;
                names = new String[] { "relname", "indisprimary", "indisunique", "indisclustered", "indisvalid", "pg_get_indexdef", "constraintdef", "contype", "condeferrable", "condeferred", "reltablespace" };
                types = new PostgresType[] { IDENT_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, INDEXDEF_PG_TYPE, CHAR0_PG_TYPE, CHAR0_PG_TYPE, BOOL_PG_TYPE, BOOL_PG_TYPE, INT2_PG_TYPE };
            }
            break;
        case PSQL_DESCRIBE_FOREIGN_KEYS_1:
            ncols = 2;
            names = new String[] { "conname", "condef" };
            types = new PostgresType[] { IDENT_PG_TYPE, CONDEF_PG_TYPE };
            break;
        case PSQL_DESCRIBE_FOREIGN_KEYS_2:
            ncols = 3;
            names = new String[] { "conname", "conrelid", "condef" };
            types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, CONDEF_PG_TYPE };
            break;
        case PSQL_DESCRIBE_TRIGGERS:
            ncols = (groups.get(1) != null) ? 3 : 2;
            names = new String[] { "tgname", "tgdef", "tdenabled" };
            types = new PostgresType[] { IDENT_PG_TYPE, CONDEF_PG_TYPE, BOOL_PG_TYPE };
            break;
        case PSQL_DESCRIBE_VIEW:
            ncols = 1;
            names = new String[] { "pg_get_viewdef" };
            types = new PostgresType[] { VIEWDEF_PG_TYPE };
            break;
        case CHARTIO_TABLES:
            ncols = 5;
            names = new String[] { "table_cat", "table_schem", "table_name", "table_type", "remarks" };
            types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, LIST_TYPE_PG_TYPE, CHAR0_PG_TYPE };
            break;
        case CHARTIO_PRIMARY_KEYS:
            ncols = 6;
            names = new String[] { "table_cat", "table_schem", "table_name", "column_name", "key_seq", "pk_name" };
            types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, INT2_PG_TYPE, IDENT_PG_TYPE };
            break;
        case CHARTIO_FOREIGN_KEYS:
            ncols = 14;
            names = new String[] { "pktable_cat", "pktable_schem", "pktable_name", "pkcolumn_name", "fktable_cat", "fktable_schem", "fktable_name", "fkcolumn_name", "key_seq", "update_rule", "delete_rule", "fk_name", "pk_name", "deferrability" };
            types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, INT2_PG_TYPE, INT2_PG_TYPE, INT2_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, INT2_PG_TYPE };
            break;
        case CHARTIO_COLUMNS:
            ncols = 12;
            names = new String[] { "nspname", "relname", "attname", "atttypid", "attnotnull", "atttypmod", "attlen", "attnum", "adsrc", "description", "typbasetype", "typtype" };
            types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, IDENT_PG_TYPE, OID_PG_TYPE, CHAR1_PG_TYPE, INT4_PG_TYPE, INT2_PG_TYPE, INT2_PG_TYPE, CHAR0_PG_TYPE, CHAR0_PG_TYPE, OID_PG_TYPE, CHAR1_PG_TYPE };
            break;
        case CHARTIO_MAX_KEYS_SETTING:
            ncols = 1;
            names = new String[] { "setting" };
            types = new PostgresType[] { DEFVAL_PG_TYPE };
            break;
        case CLSQL_LIST_OBJECTS:
            ncols = 1;
            names = new String[] { "relname" };
            types = new PostgresType[] { IDENT_PG_TYPE };
            break;
        case CLSQL_LIST_ATTRIBUTES:
            ncols = 1;
            names = new String[] { "attname" };
            types = new PostgresType[] { IDENT_PG_TYPE };
            break;
        case CLSQL_ATTRIBUTE_TYPE:
            ncols = 4;
            names = new String[] { "typname", "attlen", "atttypmod", "attnotnull" };
            types = new PostgresType[] { IDENT_PG_TYPE, INT2_PG_TYPE, INT4_PG_TYPE, CHAR1_PG_TYPE };
            break;
        case POSTMODERN_LIST:
            ncols = 1;
            names = new String[] { "relname" };
            types = new PostgresType[] { IDENT_PG_TYPE };
            break;
        case POSTMODERN_EXISTS:
            ncols = 1;
            names = new String[] { "?column?" };
            types = new PostgresType[] { BOOL_PG_TYPE };
            break;
        case POSTMODERN_TABLE_DESCRIPTION:
            ncols = 4;
            names = new String[] { "attname", "typname", "?column?", "attnum" };
            types = new PostgresType[] { IDENT_PG_TYPE, IDENT_PG_TYPE, BOOL_PG_TYPE, INT2_PG_TYPE };
            break;
        default:
            return;
        }

        PostgresServerSession server = context.getServer();
        PostgresMessenger messenger = server.getMessenger();
        if (params) {
            messenger.beginMessage(PostgresMessages.PARAMETER_DESCRIPTION_TYPE.code());
            messenger.writeShort(0);
            messenger.sendMessage();
        }
        messenger.beginMessage(PostgresMessages.ROW_DESCRIPTION_TYPE.code());
        messenger.writeShort(ncols);
        for (int i = 0; i < ncols; i++) {
            PostgresType type = types[i];
            messenger.writeString(names[i]); // attname
            messenger.writeInt(0);    // attrelid
            messenger.writeShort(0);  // attnum
            messenger.writeInt(type.getOid()); // atttypid
            messenger.writeShort(type.getLength()); // attlen
            messenger.writeInt(type.getModifier()); // atttypmod
            messenger.writeShort(0);
        }
        messenger.sendMessage();
    }

    @Override
    public TransactionMode getTransactionMode() {
        return TransactionMode.READ;
    }

    @Override
    public TransactionAbortedMode getTransactionAbortedMode() {
        return TransactionAbortedMode.NOT_ALLOWED;
    }

    @Override
    public AISGenerationMode getAISGenerationMode() {
        return AISGenerationMode.ALLOWED;
    }

    @Override
    public int execute(PostgresQueryContext context, QueryBindings bindings, int maxrows) throws IOException {
        PostgresServerSession server = context.getServer();
        PostgresMessenger messenger = server.getMessenger();
        int nrows = 0;
        switch (query) {
        case ODBC_LO_TYPE_QUERY:
            nrows = odbcLoTypeQuery(context, server, messenger, maxrows);
            break;
        case SEQUEL_B_TYPE_QUERY:
            nrows = sequelBTypeQuery(context, server, messenger, maxrows);
            break;
        case NPGSQL_TYPE_QUERY:
            nrows = npgsqlTypeQuery(context, server, messenger, maxrows);
            break;
        case PSQL_LIST_SCHEMAS:
            nrows = psqlListSchemasQuery(context, server, messenger, maxrows);
            break;
        case PSQL_LIST_TABLES:
            nrows = psqlListTablesQuery(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_TABLES_1:
            nrows = psqlDescribeTables1Query(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_TABLES_2:
            nrows = psqlDescribeTables2Query(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_TABLES_2X:
            nrows = psqlDescribeTables2XQuery(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_TABLES_3:
            nrows = psqlDescribeTables3Query(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_TABLES_4A:
        case PSQL_DESCRIBE_TABLES_4B:
        case PSQL_DESCRIBE_TABLES_5:
            nrows = psqlDescribeTables4Query(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_INDEXES:
            nrows = psqlDescribeIndexesQuery(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_FOREIGN_KEYS_1:
            nrows = psqlDescribeForeignKeys1Query(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_FOREIGN_KEYS_2:
            nrows = psqlDescribeForeignKeys2Query(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_TRIGGERS:
            nrows = psqlDescribeTriggersQuery(context, server, messenger, maxrows);
            break;
        case PSQL_DESCRIBE_VIEW:
            nrows = psqlDescribeViewQuery(context, server, messenger, maxrows);
            break;
        case CHARTIO_TABLES:
            nrows = chartioTablesQuery(context, server, messenger, maxrows);
            break;
        case CHARTIO_PRIMARY_KEYS:
            nrows = chartioPrimaryKeysQuery(context, server, messenger, maxrows);
            break;
        case CHARTIO_FOREIGN_KEYS:
            nrows = chartioForeignKeysQuery(context, server, messenger, maxrows);
            break;
        case CHARTIO_COLUMNS:
            nrows = chartioColumnsQuery(context, server, messenger, maxrows);
            break;
        case CHARTIO_MAX_KEYS_SETTING:
            nrows = chartioMaxKeysSettingQuery(context, server, messenger, maxrows);
            break;
        case CLSQL_LIST_OBJECTS:
            nrows = clsqlListObjectsQuery(context, server, messenger, maxrows);
            break;
        case CLSQL_LIST_ATTRIBUTES:
            nrows = clsqlListAttributesQuery(context, server, messenger, maxrows);
            break;
        case CLSQL_ATTRIBUTE_TYPE:
            nrows = clsqlAttributeTypeQuery(context, server, messenger, maxrows);
            break;
        case POSTMODERN_LIST:
            nrows = postmodernListQuery(context, server, messenger, maxrows);
            break;
        case POSTMODERN_EXISTS:
            nrows = postmodernExistsQuery(context, server, messenger, maxrows);
            break;
        case POSTMODERN_TABLE_DESCRIPTION:
            nrows = postmodernTableDescriptionQuery(context, server, messenger, maxrows);
            break;
        }
        {        
          messenger.beginMessage(PostgresMessages.COMMAND_COMPLETE_TYPE.code());
          messenger.writeString("SELECT " + nrows);
          messenger.sendMessage();
        }
        return nrows;
    }

    @Override
    public boolean hasAISGeneration() {
        return aisGeneration != 0;
    }

    @Override
    public void setAISGeneration(long aisGeneration) {
        this.aisGeneration = aisGeneration;
    }

    @Override
    public long getAISGeneration() {
        return aisGeneration;
    }

    @Override
    public PostgresStatement finishGenerating(PostgresServerSession server,
                                              String sql, StatementNode stmt,
                                              List<ParameterNode> params, int[] paramTypes) {
        return this;
    }

    @Override
    public boolean putInCache() {
        return false;
    }

    @Override
    public CostEstimate getCostEstimate() {
        return null;
    }

    protected void writeColumn(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger,
                               int col, Object value, PostgresType type) throws IOException {
        ServerValueEncoder encoder = server.getValueEncoder();        
        boolean binary = context.isColumnBinary(col);
        ByteArrayOutputStream bytes;
        bytes = encoder.encodePObject(value, type, binary);
        if (bytes == null) {
            messenger.writeInt(-1);
        } 
        else {
            messenger.writeInt(bytes.size());
            messenger.writeByteStream(bytes);
        }
    }

    private int odbcLoTypeQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) {
        return 0;
    }

    private int sequelBTypeQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        for (PostgresType.TypeOid pgtype : PostgresType.TypeOid.values()) {
            if (pgtype.getType() == PostgresType.TypeOid.TypType.BASE) {
                messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                messenger.writeShort(2); // 2 columns for this query
                writeColumn(context, server, messenger, 
                            0, pgtype.getOid(), OID_PG_TYPE);
                writeColumn(context, server, messenger, 
                            1, pgtype.getName(), TYPNAME_PG_TYPE);
                messenger.sendMessage();
                nrows++;
                if ((maxrows > 0) && (nrows >= maxrows)) {
                    break;
                }
            }
        }
        return nrows;
    }

    private int npgsqlTypeQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        List<String> types = new ArrayList<>();
        for (String type : groups.get(1).split(",")) {
            if ((type.charAt(0) == '\'') && (type.charAt(type.length()-1) == '\''))
                type = type.substring(1, type.length()-1);
            types.add(type);
        }
        for (PostgresType.TypeOid pgtype : PostgresType.TypeOid.values()) {
            if (types.contains(pgtype.getName())) {
                messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                messenger.writeShort(2); // 2 columns for this query
                writeColumn(context, server, messenger,  
                            0, pgtype.getName(), TYPNAME_PG_TYPE);
                writeColumn(context, server, messenger,  
                            1, pgtype.getOid(), OID_PG_TYPE);
                messenger.sendMessage();
                nrows++;
                if ((maxrows > 0) && (nrows >= maxrows)) {
                    break;
                }
            }
        }
        return nrows;
    }

    private int psqlListSchemasQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        AkibanInformationSchema ais = server.getAIS();
        List<String> names = new ArrayList<>(ais.getSchemas().keySet());
        boolean noIS = (groups.get(1) != null);
        Pattern pattern = null;
        if (groups.get(2) != null)
            pattern = Pattern.compile(groups.get(3));
        Iterator<String> iter = names.iterator();
        while (iter.hasNext()) {
            String name = iter.next();
            if ((noIS &&
                 name.equals(TableName.INFORMATION_SCHEMA) ||
                 name.equals(TableName.SECURITY_SCHEMA)) ||
                ((pattern != null) && 
                 !pattern.matcher(name).find()) ||
                !server.isSchemaAccessible(name))
                iter.remove();
        }
        Collections.sort(names);
        for (String name : names) {
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(2); // 2 columns for this query
            writeColumn(context, server, messenger, 
                        0, name, IDENT_PG_TYPE);
            writeColumn(context, server, messenger, 
                        1, null, IDENT_PG_TYPE);
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private int psqlListTablesQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        List<String> types = Arrays.asList(groups.get(1).split(","));
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        if (types.contains("'r'"))
            tables.addAll(ais.getUserTables().values());
        if (types.contains("'v'"))
            tables.addAll(ais.getViews().values());
        boolean noIS = (groups.get(2) != null) || (groups.get(3) != null);
        boolean onlyIS = (groups.get(4) != null);
        Pattern schemaPattern = null, tablePattern = null;
        if (groups.get(5) != null)
            tablePattern = Pattern.compile(groups.get(6));
        if (groups.get(7) != null)
            schemaPattern = Pattern.compile(groups.get(8));
        if (groups.get(9) != null)
            tablePattern = Pattern.compile(groups.get(10));
        Iterator<Columnar> iter = tables.iterator();
        while (iter.hasNext()) {
            TableName name = iter.next().getName();
            if (((name.getSchemaName().equals(TableName.INFORMATION_SCHEMA) ||
                  name.getSchemaName().equals(TableName.SECURITY_SCHEMA))
                 ? noIS : onlyIS) ||
                ((schemaPattern != null) && 
                 !schemaPattern.matcher(name.getSchemaName()).find()) ||
                ((tablePattern != null) && 
                 !tablePattern.matcher(name.getTableName()).find()) ||
                !server.isSchemaAccessible(name.getSchemaName()))
                iter.remove();
        }
        Collections.sort(tables, LIST_TABLES_BY_GROUP ? tablesByGroup : tablesByName);
        for (Columnar table : tables) {
            TableName name = table.getName();
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(4); // 4 columns for this query
            writeColumn(context, server, messenger,  
                        0, name.getSchemaName(), IDENT_PG_TYPE);
            writeColumn(context, server, messenger,  
                        1, name.getTableName(), IDENT_PG_TYPE);
            String type = table.isView() ? "view" : "table";
            writeColumn(context, server, messenger,  
                        2, type, LIST_TYPE_PG_TYPE);
            if (LIST_TABLES_BY_GROUP) {
                String path = null;
                if (table.isTable()) {
                    path = tableGroupPath((UserTable)table, name.getSchemaName());
                }
                writeColumn(context, server, messenger,
                            3, path, PATH_PG_TYPE);
            }
            else {
                String owner = null;
                writeColumn(context, server, messenger, 
                            3, owner, IDENT_PG_TYPE);
            }
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private static final Comparator<Columnar> tablesByName = new Comparator<Columnar>() {
        @Override
        public int compare(Columnar t1, Columnar t2) {
            return t1.getName().compareTo(t2.getName());
        }
    };

    private static final Comparator<Columnar> tablesByGroup = new Comparator<Columnar>() {
        @Override
        public int compare(Columnar t1, Columnar t2) {
            TableName n1 = t1.getName();
            TableName n2 = t2.getName();
            Group g1 = null, g2 = null;
            Integer d1 = null, d2 = null;
            if (t1.isTable()) {
                UserTable ut1 = ((UserTable)t1);
                g1 = ut1.getGroup();
                d1 = ut1.getDepth();
            }
            if (t2.isTable()) {
                UserTable ut2 = ((UserTable)t2);
                g2 = ut2.getGroup();
                d2 = ut2.getDepth();
            }
            if (g1 != g2)
                return ((g1 == null) ? n1 : g1.getName()).compareTo((g2 == null) ? n2 : g2.getName());
            if ((d1 != null) && !d1.equals(d2))
                return d1.compareTo(d2);
            else
                return n1.compareTo(n2);
        }
    };

    private String tableGroupPath(UserTable table, String schemaName) {
        StringBuilder str = new StringBuilder();
        do {
            if (str.length() > 0)
                str.insert(0, '/');
            str.insert(0, table.getName().getTableName());
            if (!schemaName.equals(table.getName().getSchemaName())) {
                str.insert(0, '.');
                str.insert(0, table.getName().getSchemaName());
            }
            table = table.parentTable();
        } while (table != null);
        return str.toString();
    }

    private int psqlDescribeTables1Query(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        Map<Integer,TableName> nonTableNames = null;
        List<TableName> names = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        names.addAll(ais.getUserTables().keySet());
        names.addAll(ais.getViews().keySet());
        Pattern schemaPattern = null, tablePattern = null;
        if (groups.get(1) != null)
            schemaPattern = Pattern.compile(groups.get(2));
        if (groups.get(3) != null)
            tablePattern = Pattern.compile(groups.get(4));
        if (groups.get(5) != null)
            schemaPattern = Pattern.compile(groups.get(6));
        Iterator<TableName> iter = names.iterator();
        while (iter.hasNext()) {
            TableName name = iter.next();
            if (((schemaPattern != null) && 
                 !schemaPattern.matcher(name.getSchemaName()).find()) ||
                ((tablePattern != null) && 
                 !tablePattern.matcher(name.getTableName()).find()) ||
                !server.isSchemaAccessible(name.getSchemaName()))
                iter.remove();
        }
        Collections.sort(names);
        for (TableName name : names) {
            int id;
            Columnar table = ais.getColumnar(name);
            if (table.isTable())
                id = ((Table)table).getTableId();
            else {
                if (nonTableNames == null)
                    nonTableNames = new HashMap<>();
                id = - (nonTableNames.size() + 1);
                nonTableNames.put(id, name);
            }
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(3); // 3 columns for this query
            writeColumn(context, server, messenger,  
                        0, id, OID_PG_TYPE);
            writeColumn(context, server, messenger,  
                        1, name.getSchemaName(), IDENT_PG_TYPE);
            writeColumn(context, server, messenger,  
                        2, name.getTableName(), IDENT_PG_TYPE);
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        server.setAttribute("psql_nonTableNames", nonTableNames);
        return nrows;
    }

    private Columnar getTableById(PostgresServerSession server, String group) {
        AkibanInformationSchema ais = server.getAIS();
        int id = Integer.parseInt(group);
        if (id < 0) {
            Map<Integer,TableName> nonTableNames = (Map<Integer,TableName>)
                server.getAttribute("psql_nonTableNames");
            if (nonTableNames != null) {
                TableName name = nonTableNames.get(id);
                if (name != null) {
                    return ais.getColumnar(name);
                }
            }
        }
        else {
            UserTable table = ais.getUserTable(id);
            if (server.isSchemaAccessible(table.getName().getSchemaName())) {
                return table;
            }
        }
        return null;
    }

    private int psqlDescribeTables2Query(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        Columnar table = getTableById(server, groups.get(1));
        if (table == null) return 0;
        messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
        messenger.writeShort(8); // 8 columns for this query
        writeColumn(context, server, messenger,  // relchecks
                    0, (short)0, INT2_PG_TYPE);
        writeColumn(context, server, messenger,  // relkind
                    1, table.isView() ? "v" : "r", CHAR1_PG_TYPE);
        writeColumn(context, server, messenger, // relhasindex
                    2, hasIndexes(table) ? "t" : "f", CHAR1_PG_TYPE);
        writeColumn(context, server, messenger,  // relhasrules
                    3, false, BOOL_PG_TYPE);
        writeColumn(context, server, messenger,  // relhastriggers
                    4, hasTriggers(table) ? "t" : "f", CHAR1_PG_TYPE);
        writeColumn(context, server, messenger,  // relhasoids
                    5, false, BOOL_PG_TYPE);
        writeColumn(context, server, messenger, 
                    6, "", CHAR0_PG_TYPE);
        writeColumn(context, server, messenger, // reltablespace
                    7, 0, OID_PG_TYPE);
        messenger.sendMessage();
        return 1;
    }

    private int psqlDescribeTables2XQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        Columnar table = getTableById(server, groups.get(2));
        if (table == null) return 0;
        boolean hasTablespace = (groups.get(1) != null);
        messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
        messenger.writeShort(hasTablespace ? 7 : 5); // 5-7 columns for this query
        writeColumn(context, server, messenger,  // relhasindex
                    0, hasIndexes(table) ? "t" : "f", CHAR1_PG_TYPE);
        writeColumn(context, server, messenger, // relkind
                    1, table.isView() ? "v" : "r", CHAR1_PG_TYPE);
        writeColumn(context, server, messenger,  // relchecks
                    2, (short)0, INT2_PG_TYPE);
        writeColumn(context, server, messenger,  // reltriggers
                    3, hasTriggers(table) ? (short)1 : (short)0, INT2_PG_TYPE);
        writeColumn(context, server, messenger,  // relhasrules
                    4, false, BOOL_PG_TYPE);
        if (hasTablespace) {
            writeColumn(context, server, messenger, // relhasoids
                        5, false, BOOL_PG_TYPE);
            writeColumn(context, server, messenger, // reltablespace
                        6, 0, OID_PG_TYPE);
        }
        messenger.sendMessage();
        return 1;
    }

    private int psqlDescribeTables3Query(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        Columnar table = getTableById(server, groups.get(2));
        if (table == null) return 0;
        boolean hasCollation = (groups.get(1) != null);
        for (Column column : table.getColumns()) {
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(hasCollation ? 6 : 5); // 5-6 columns for this query
            writeColumn(context, server, messenger,  // attname
                        0, column.getName(), IDENT_PG_TYPE);
            writeColumn(context, server, messenger, // format_type
                        1, column.getTypeDescription(), TYPNAME_PG_TYPE);
            String defval = null;
            if (column.getDefaultValue() != null)
                defval = column.getDefaultValue();
            else if (column.getDefaultFunction() != null)
                defval = column.getDefaultFunction() + "()";
            if ((defval != null) && (defval.length() > 128))
                defval = defval.substring(0, 128);
            writeColumn(context, server, messenger,  
                        2, defval, DEFVAL_PG_TYPE);
            // This should use BOOL_PG_TYPE, except that does true/false, not t/f.
            writeColumn(context, server, messenger, // attnotnull
                        3, column.getNullable() ? "f" : "t", CHAR1_PG_TYPE);
            writeColumn(context, server, messenger,  // attnum
                        4, column.getPosition().shortValue(), INT2_PG_TYPE);
            if (hasCollation) {
                CharsetAndCollation charAndColl = null;
                switch (column.getType().akType()) {
                case VARCHAR:
                case TEXT:
                    charAndColl = column.getCharsetAndCollation();
                    break;
                }
                writeColumn(context, server, messenger, // attcollation
                            5, (charAndColl == null) ? null : charAndColl.collation(), 
                            IDENT_PG_TYPE);
            }
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private int psqlDescribeTables4Query(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        Columnar table = getTableById(server, groups.get(1));
        if (table == null) return 0;
        return 0;
    }

    private int psqlDescribeIndexesQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        Columnar columnar = getTableById(server, groups.get(4));
        if ((columnar == null) || !columnar.isTable()) return 0;
        UserTable table = (UserTable)columnar;
        Map<String,Index> indexes = new TreeMap<>();
        for (Index index : table.getIndexesIncludingInternal()) {
            if (isAkibanPKIndex(index)) continue;
            indexes.put(index.getIndexName().getName(), index);
        }
        for (Index index : table.getGroupIndexes()) {
            if (isTableReferenced(table, index)) {
                indexes.put(index.getIndexName().getName(), index);
            }
        }
        for (Index index : table.getFullTextIndexes()) {
            indexes.put(index.getIndexName().getName(), index);
        }
        int ncols;
        if (groups.get(1) == null) {
            ncols = 4;
        }
        else if (groups.get(2) == null) {
            ncols = 6;
        }
        else if (groups.get(3) == null) {
            ncols = 7;
        }
        else {
            ncols = 11;
        }
        for (Index index : indexes.values()) {
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(ncols); // 4-5-7-11 columns for this query
            int col = 0;
            writeColumn(context, server, messenger,  // relname
                        col++, index.getIndexName().getName(), IDENT_PG_TYPE);
            writeColumn(context, server, messenger,  // indisprimary
                        col++, (index.getIndexName().getName().equals(Index.PRIMARY_KEY_CONSTRAINT)) ? "t" : "f", CHAR1_PG_TYPE);
            writeColumn(context, server, messenger,  // indisunique
                        col++, (index.isUnique()) ? "t" : "f", CHAR1_PG_TYPE);
            if (ncols > 4) {
                writeColumn(context, server, messenger,  // indisclustered
                            col++, false, BOOL_PG_TYPE);
                if (ncols > 6) {
                    writeColumn(context, server, messenger,  // indisvalid
                                col++, "t", CHAR1_PG_TYPE);
                }
            }
            writeColumn(context, server, messenger,  // pg_get_indexdef
                        col++, formatIndexdef(index, table), INDEXDEF_PG_TYPE);
            if (ncols > 7) {
                writeColumn(context, server, messenger,  // constraintdef
                            col++, null, CHAR0_PG_TYPE);
                writeColumn(context, server, messenger,  // contype
                            col++, null, CHAR0_PG_TYPE);
                writeColumn(context, server, messenger,  // condeferragble
                            col++, false, BOOL_PG_TYPE);
                writeColumn(context, server, messenger,  // condeferred
                            col++, false, BOOL_PG_TYPE);
            }
            if (ncols > 5) {
                writeColumn(context, server, messenger, // reltablespace
                            col++, 0, OID_PG_TYPE);
            }
            assert (col == ncols);
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private int psqlDescribeForeignKeys1Query(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        Columnar columnar = getTableById(server, groups.get(1));
        if ((columnar == null) || !columnar.isTable()) return 0;
        Join join = ((UserTable)columnar).getParentJoin();
        if (join == null) return 0;
        messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
        messenger.writeShort(2); // 2 columns for this query
        writeColumn(context, server, messenger,  // conname
                    0, join.getName(), IDENT_PG_TYPE);
        writeColumn(context, server, messenger, // condef
                    1, formatCondef(join, false), CONDEF_PG_TYPE);
        messenger.sendMessage();
        nrows++;
        return nrows;
    }

    private int psqlDescribeForeignKeys2Query(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        Columnar columnar = getTableById(server, groups.get(1));
        if ((columnar == null) || !columnar.isTable()) return 0;
        for (Join join : ((UserTable)columnar).getChildJoins()) {
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(3); // 3 columns for this query
            writeColumn(context, server, messenger,  // conname
                        0, join.getName(), IDENT_PG_TYPE);
            writeColumn(context, server, messenger,  // conrelid
                        1, join.getChild().getName().getTableName(), IDENT_PG_TYPE);
            writeColumn(context, server, messenger,  // condef
                        2, formatCondef(join, true), CONDEF_PG_TYPE);
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private int psqlDescribeTriggersQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        Columnar columnar = getTableById(server, groups.get(2));
        return 0;
    }

    private boolean hasIndexes(Columnar table) {
        if (!table.isTable())
            return false;
        Collection<? extends Index> indexes = ((UserTable)table).getIndexes();
        if (indexes.isEmpty())
            return false;
        if (indexes.size() > 1)
            return true;
        if (isAkibanPKIndex(indexes.iterator().next()))
            return false;
        return true;
    }

    private boolean hasTriggers(Columnar table) {
        if (!table.isTable())
            return false;
        UserTable userTable = (UserTable)table;
        if (userTable.getParentJoin() != null)
            return true;
        if (!userTable.getChildJoins().isEmpty())
            return true;
        return false;
    }

    private boolean isAkibanPKIndex(Index index) {
        List<IndexColumn> indexColumns = index.getKeyColumns();
        return ((indexColumns.size() == 1) && 
                indexColumns.get(0).getColumn().isAkibanPKColumn());
    }

    private boolean isTableReferenced(UserTable table, Index groupIndex) {
        for (IndexColumn indexColumn : groupIndex.getKeyColumns()) {
            // A table may only be referenced by hKey components, in
            // which case we don't want to display it.
            if (indexColumn.getColumn().getTable() == table) {
                return true;
            }
        }
        return false;
    }

    private String formatIndexdef(Index index, UserTable table) {
        StringBuilder str = new StringBuilder();
        // Postgres CREATE INDEX has USING method, btree|hash|gist|gin|...
        // That is where the client starts including output.
        // Only issue is that for PRIMARY KEY, it prints a comma in
        // anticipation of some method word before the column.
        str.append(" USING ");
        int firstFunctionColumn = Integer.MAX_VALUE;
        int lastFunctionColumn = Integer.MIN_VALUE;
        switch (index.getIndexMethod()) {
        case NORMAL:
            break;
        case Z_ORDER_LAT_LON:
            firstFunctionColumn = index.firstSpatialArgument();
            lastFunctionColumn = firstFunctionColumn + index.dimensions() - 1;
            break;
        case FULL_TEXT:
        default:
            firstFunctionColumn = 0;
            lastFunctionColumn = index.getKeyColumns().size() - 1;
            break;
        }
        str.append("(");
        boolean first = true;
        for (IndexColumn icolumn : index.getKeyColumns()) {
            Column column = icolumn.getColumn();
            if (first) {
                first = false;
            }
            else {
                str.append(", ");
            }
            int positionInIndex = icolumn.getPosition();
            if (positionInIndex == firstFunctionColumn) {
                str.append(index.getIndexMethod().name());
                str.append('(');
            }
            if (column.getTable() != table) {
                str.append(column.getTable().getName().getTableName())
                   .append(".");
            }
            str.append(column.getName());
            if (positionInIndex == lastFunctionColumn) {
                str.append(')');
            }
        }
        str.append(")");
        if (index.isGroupIndex()) {
            str.append(" USING " + index.getJoinType() + " JOIN");
        }
        return str.toString();
    }

    private String formatCondef(Join parentJoin, boolean forParent) {
        StringBuilder str = new StringBuilder();
        str.append("GROUPING FOREIGN KEY(");
        boolean first = true;
        for (JoinColumn joinColumn : parentJoin.getJoinColumns()) {
            if (first) {
                first = false;
            }
            else {
                str.append(", ");
            }
            str.append(joinColumn.getChild().getName());
        }
        str.append(") REFERENCES");
        if (!forParent) {
            str.append(" ");
            str.append(parentJoin.getParent().getName().getTableName());
        }
        str.append("(");
        first = true;
        for (JoinColumn joinColumn : parentJoin.getJoinColumns()) {
            if (first) {
                first = false;
            }
            else {
                str.append(", ");
            }
            str.append(joinColumn.getParent().getName());
        }
        str.append(")");
        return str.toString();
    }

    private int psqlDescribeViewQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        Columnar table = getTableById(server, groups.get(1));
        if ((table == null) || !table.isView()) return 0;
        View view = (View)table;
        messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
        messenger.writeShort(1); // 1 column for this query
        writeColumn(context, server, messenger,  // pg_get_viewdef
                    0, view.getDefinition(), VIEWDEF_PG_TYPE);
        messenger.sendMessage();
        return 1;
    }

    private int chartioTablesQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        tables.addAll(ais.getUserTables().values());
        tables.addAll(ais.getViews().values());
        Iterator<Columnar> iter = tables.iterator();
        while (iter.hasNext()) {
            TableName name = iter.next().getName();
            if (name.getSchemaName().equals(TableName.INFORMATION_SCHEMA) ||
                name.getSchemaName().equals(TableName.SECURITY_SCHEMA) ||
                !server.isSchemaAccessible(name.getSchemaName()))
                iter.remove();
        }
        Collections.sort(tables, tablesByName);
        for (Columnar table : tables) {
            TableName name = table.getName();
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(5); // 5 columns for this query
            writeColumn(context, server, messenger,  
                        0, null, IDENT_PG_TYPE);
            writeColumn(context, server, messenger,  
                        1, name.getSchemaName(), IDENT_PG_TYPE);
            writeColumn(context, server, messenger,  
                        2, name.getTableName(), IDENT_PG_TYPE);
            String type = table.isView() ? "VIEW" : "TABLE";
            writeColumn(context, server, messenger,  
                        3, type, LIST_TYPE_PG_TYPE);
            writeColumn(context, server, messenger,  
                        4, null, CHAR0_PG_TYPE);
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private int chartioPrimaryKeysQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String name = groups.get(1);
        AkibanInformationSchema ais = server.getAIS();
        List<UserTable> tables = new ArrayList<>();
        for (UserTable table : ais.getUserTables().values()) {
            TableName tableName = table.getName();
            if (server.isSchemaAccessible(tableName.getSchemaName()) &&
                tableName.getTableName().equalsIgnoreCase(name)) {
                tables.add(table);
            }
        }
        Collections.sort(tables, tablesByName);
        rows:
        for (UserTable table : tables) {
            TableIndex index = table.getIndex(Index.PRIMARY_KEY_CONSTRAINT);
            if (index != null) {
                TableName tableName = table.getName();
                for (IndexColumn column : index.getKeyColumns()) {
                    messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                    messenger.writeShort(6); // 6 columns for this query
                    writeColumn(context, server, messenger,  
                                0, null, IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                1, tableName.getSchemaName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                2, tableName.getTableName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                3, column.getColumn().getName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                4, (short)(column.getPosition() + 1), INT2_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                5, index.getIndexName().getName(), IDENT_PG_TYPE);
                    messenger.sendMessage();
                    nrows++;
                    if ((maxrows > 0) && (nrows >= maxrows)) {
                        break rows;
                    }
                }
            }
        }
        return nrows;
    }

    private int chartioForeignKeysQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String name = groups.get(1);
        AkibanInformationSchema ais = server.getAIS();
        List<UserTable> tables = new ArrayList<>();
        for (UserTable table : ais.getUserTables().values()) {
            TableName tableName = table.getName();
            if (server.isSchemaAccessible(tableName.getSchemaName()) &&
                tableName.getTableName().equalsIgnoreCase(name)) {
                tables.add(table);
            }
        }
        Collections.sort(tables, tablesByName);
        rows:
        for (UserTable table : tables) {
            Join join = table.getParentJoin();
            if (join != null) {
                TableName childName = table.getName();
                TableName parentName = join.getParent().getName();
                for (JoinColumn column : join.getJoinColumns()) {
                    messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                    messenger.writeShort(14); // 14 columns for this query
                    writeColumn(context, server, messenger,  
                                0, null, IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                1, parentName.getSchemaName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                2, parentName.getTableName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                2, column.getParent().getName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                4, null, IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                5, childName.getSchemaName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                6, childName.getTableName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                7, column.getChild().getName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                8, (short)(column.getParent().getPosition() + 1), INT2_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                9, (short)3, INT2_PG_TYPE); // no action
                    writeColumn(context, server, messenger,  
                                10, (short)3, INT2_PG_TYPE); // no action
                    writeColumn(context, server, messenger,  
                                11, join.getName(), IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                12, Index.PRIMARY_KEY_CONSTRAINT, IDENT_PG_TYPE);
                    writeColumn(context, server, messenger,  
                                13, (short)7, INT2_PG_TYPE); // not deferrable
                    messenger.sendMessage();
                    nrows++;
                    if ((maxrows > 0) && (nrows >= maxrows)) {
                        break rows;
                    }
                }
            }
        }
        return nrows;
    }

    private int chartioColumnsQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String name = groups.get(1);
        AkibanInformationSchema ais = server.getAIS();
        List<UserTable> tables = new ArrayList<>();
        for (UserTable table : ais.getUserTables().values()) {
            TableName tableName = table.getName();
            if (server.isSchemaAccessible(tableName.getSchemaName()) &&
                tableName.getTableName().equalsIgnoreCase(name)) {
                tables.add(table);
            }
        }
        Collections.sort(tables, tablesByName);
        rows:
        for (UserTable table : tables) {
            TableName tableName = table.getName();
            for (Column column : table.getColumns()) {
                PostgresType type = PostgresType.fromAIS(column);
                messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                messenger.writeShort(12); // 12 columns for this query
                writeColumn(context, server, messenger,  
                            0, tableName.getSchemaName(), IDENT_PG_TYPE);
                writeColumn(context, server, messenger,  
                            1, tableName.getTableName(), IDENT_PG_TYPE);
                writeColumn(context, server, messenger,  
                            2, column.getName(), IDENT_PG_TYPE);
                writeColumn(context, server, messenger,  
                            3, type.getOid(), OID_PG_TYPE);
                writeColumn(context, server, messenger,  
                            4, column.getNullable() ? "t" : "f", CHAR1_PG_TYPE);
                writeColumn(context, server, messenger,  
                            5, type.getModifier(), INT4_PG_TYPE);
                writeColumn(context, server, messenger,  
                            6, type.getLength(), INT2_PG_TYPE);
                writeColumn(context, server, messenger,  
                            7, (short)(column.getPosition() + 1), INT2_PG_TYPE);
                writeColumn(context, server, messenger,  
                            8, null, CHAR0_PG_TYPE);
                writeColumn(context, server, messenger,  
                            9, null, CHAR0_PG_TYPE);
                writeColumn(context, server, messenger,  
                            10, 0, OID_PG_TYPE);
                writeColumn(context, server, messenger,  
                            11, "b", CHAR1_PG_TYPE);
                messenger.sendMessage();
                nrows++;
                if ((maxrows > 0) && (nrows >= maxrows)) {
                    break rows;
                }
            }
        }
        return nrows;
    }

    private int chartioMaxKeysSettingQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
        messenger.writeShort(1); // 1 column for this query
        writeColumn(context, server, messenger,  
                    0, "8", DEFVAL_PG_TYPE); // Postgres has 32 by default
        messenger.sendMessage();
        return 1;
    }

    private int clsqlListObjectsQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String type = groups.get(1);
        String owner = groups.get(2);
        boolean noIS = (groups.get(3) != null);
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        if ("r".equals(type))
            tables.addAll(ais.getUserTables().values());
        if ("v".equals(type))
            tables.addAll(ais.getViews().values());
        Iterator<Columnar> iter = tables.iterator();
        while (iter.hasNext()) {
            TableName name = iter.next().getName();
            if (((owner != null) ?
                 !owner.equals(name.getSchemaName()) :
                 noIS &&
                 (name.getSchemaName().equals(TableName.INFORMATION_SCHEMA) ||
                  name.getSchemaName().equals(TableName.SECURITY_SCHEMA))) ||
                !server.isSchemaAccessible(name.getSchemaName()))
                iter.remove();
        }
        Collections.sort(tables, tablesByName);
        for (Columnar table : tables) {
            TableName name = table.getName();
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(1); // 1 column for this query
            writeColumn(context, server, messenger,  
                        0, name.getTableName(), IDENT_PG_TYPE);
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private int clsqlListAttributesQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String relname = groups.get(1);
        String owner = groups.get(2);
        boolean noIS = (groups.get(3) != null);
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        if (owner != null) {
            if (server.isSchemaAccessible(owner)) {
                Columnar table = ais.getColumnar(owner, relname);
                if (table != null) {
                    tables.add(table);
                }
            }
        }
        else {
            tables.addAll(ais.getUserTables().values());
            tables.addAll(ais.getViews().values());
            Iterator<Columnar> iter = tables.iterator();
            while (iter.hasNext()) {
                TableName name = iter.next().getName();
                if (!name.getTableName().equals(relname) ||
                    (noIS &&
                     (name.getSchemaName().equals(TableName.INFORMATION_SCHEMA) ||
                      name.getSchemaName().equals(TableName.SECURITY_SCHEMA))) ||
                    !server.isSchemaAccessible(name.getSchemaName()))
                    iter.remove();
            }
            Collections.sort(tables, tablesByName);
        }
        rows:
        for (Columnar table : tables) {
            for (Column column : table.getColumns()) {
                messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                messenger.writeShort(1); // 1 column for this query
                writeColumn(context, server, messenger,  
                            0, column.getName(), IDENT_PG_TYPE);
                messenger.sendMessage();
                nrows++;
                if ((maxrows > 0) && (nrows >= maxrows)) {
                    break rows;
                }
            }
        }
        return nrows;
    }

    private int clsqlAttributeTypeQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String relname = groups.get(1);
        String attname = groups.get(2);
        String owner = groups.get(3);
        boolean noIS = (groups.get(4) != null);
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        if (owner != null) {
            Columnar table = ais.getColumnar(owner, relname);
            if (table != null) {
                tables.add(table);
            }
        }
        else {
            tables.addAll(ais.getUserTables().values());
            tables.addAll(ais.getViews().values());
            Iterator<Columnar> iter = tables.iterator();
            while (iter.hasNext()) {
                TableName name = iter.next().getName();
                if (!name.getTableName().equals(relname) ||
                    (noIS &&
                     (name.getSchemaName().equals(TableName.INFORMATION_SCHEMA) ||
                      name.getSchemaName().equals(TableName.SECURITY_SCHEMA))) ||
                    !server.isSchemaAccessible(name.getSchemaName()))
                    iter.remove();
            }
            Collections.sort(tables, tablesByName);
        }
        rows:
        for (Columnar table : tables) {
            Column column = table.getColumn(attname);
            if (column != null) {
                PostgresType type = PostgresType.fromAIS(column);
                messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                messenger.writeShort(4); // 4 columns for this query
                writeColumn(context, server, messenger,  
                            0, type.getTypeName(), IDENT_PG_TYPE);
                writeColumn(context, server, messenger,  
                            1, type.getLength(), INT2_PG_TYPE);
                writeColumn(context, server, messenger,  
                            2, type.getModifier(), INT4_PG_TYPE);
                writeColumn(context, server, messenger,  
                            3, column.getNullable() ? "t" : "f", CHAR1_PG_TYPE);
                messenger.sendMessage();
                nrows++;
                if ((maxrows > 0) && (nrows >= maxrows)) {
                    break rows;
                }
            }
        }
        return nrows;
    }

    private int postmodernListQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String type = groups.get(1);
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        if ("r".equals(type))
            tables.addAll(ais.getUserTables().values());
        if ("v".equals(type))
            tables.addAll(ais.getViews().values());
        Iterator<Columnar> iter = tables.iterator();
        while (iter.hasNext()) {
            TableName name = iter.next().getName();
            if (name.getSchemaName().equals(TableName.INFORMATION_SCHEMA) ||
                name.getSchemaName().equals(TableName.SECURITY_SCHEMA) ||
                !server.isSchemaAccessible(name.getSchemaName()))
                iter.remove();
        }
        Collections.sort(tables, tablesByName);
        for (Columnar table : tables) {
            TableName name = table.getName();
            messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
            messenger.writeShort(1); // 1 column for this query
            writeColumn(context, server, messenger,  
                        0, name.getTableName(), IDENT_PG_TYPE);
            messenger.sendMessage();
            nrows++;
            if ((maxrows > 0) && (nrows >= maxrows)) {
                break;
            }
        }
        return nrows;
    }

    private int postmodernExistsQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        String type = groups.get(1);
        String relname = groups.get(2);
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        if ("r".equals(type))
            tables.addAll(ais.getUserTables().values());
        if ("v".equals(type))
            tables.addAll(ais.getViews().values());
        boolean exists = false;
        for (Columnar table : tables) {
            TableName name = table.getName();
            if (name.getTableName().equals(relname) &&
                server.isSchemaAccessible(name.getSchemaName())) {
                exists = true;
                break;
            }
        }
        messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
        messenger.writeShort(1); // 1 column for this query
        writeColumn(context, server, messenger,  
                    0, exists, BOOL_PG_TYPE);
        messenger.sendMessage();
        return 1;
    }

    private int postmodernTableDescriptionQuery(PostgresQueryContext context, PostgresServerSession server, PostgresMessenger messenger, int maxrows) throws IOException {
        int nrows = 0;
        String relname = groups.get(1);
        String namespace = groups.get(2);
        List<Columnar> tables = new ArrayList<>();
        AkibanInformationSchema ais = server.getAIS();
        if (namespace != null) {
            if (server.isSchemaAccessible(namespace)) {
                Columnar table = ais.getColumnar(namespace, relname);
                if (table != null) {
                    tables.add(table);
                }
            }
        }
        else {
            tables.addAll(ais.getUserTables().values());
            tables.addAll(ais.getViews().values());
            Iterator<Columnar> iter = tables.iterator();
            while (iter.hasNext()) {
                TableName name = iter.next().getName();
                if (!name.getTableName().equals(relname) ||
                    !server.isSchemaAccessible(name.getSchemaName()))
                    iter.remove();
            }
            Collections.sort(tables, tablesByName);
        }
        rows:
        for (Columnar table : tables) {
            for (Column column : table.getColumns()) {
                PostgresType type = PostgresType.fromAIS(column);
                messenger.beginMessage(PostgresMessages.DATA_ROW_TYPE.code());
                messenger.writeShort(4); // 4 columns for this query
                writeColumn(context, server, messenger,  
                            0, column.getName(), IDENT_PG_TYPE);
                writeColumn(context, server, messenger,  
                            1, type.getTypeName(), IDENT_PG_TYPE);
                writeColumn(context, server, messenger,  
                            2, column.getNullable(), BOOL_PG_TYPE);
                writeColumn(context, server, messenger,  
                            3, (short)(column.getPosition() + 1), INT2_PG_TYPE);
                messenger.sendMessage();
                nrows++;
                if ((maxrows > 0) && (nrows >= maxrows)) {
                    break rows;
                }
            }
        }
        return nrows;
    }

}