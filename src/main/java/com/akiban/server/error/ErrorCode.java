/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.error;

import java.util.ResourceBundle;

/**
 * The error codes that are part of the Akiban Server's public API. 
 * 
 * From the SQL Standard, the SQLSTATE (ErrorCodes) are a 2 character class value
 * followed by a 3 character sub-class value. These characters are either digits or
 * upper-case latin characters. (0-9 or A-Z). 
 * 
 * Class values that begin with 0 through 4 or A through H are defined by the standard.
 * Class values that begin with 5 through 9 or I through Z are implementation defined
 * errors, i.e. specific to the Akiban implementation.
 *  
 * The subclass '000' means no subclass to the given SQLSTATE class
 * 
 * Sub-class values that begin with 0-4 or A-H are defined by either the SQL standard, or
 * another standard. Sub-class values beginning with 5-9 or I-Z are implementation defined 
 * subclasses. 
 * 
 * The SQLCODE encompasses more than errors, though most of the SQLCODES do indicate errors or
 * exceptions. The ErrorCode implements the errors and exceptions, but not any of the non-error
 * SQLCODES.  
 * 
 * The specific division between a class and a subclass is not made clear by the standard. 
 * 
 */
public enum ErrorCode {
    
    // Class 00 - successful completion
    SUCCESSFUL_COMPLETION ("00", "000", Importance.TRACE, null),
    
    // Class 01 - warning
    // No Warnings defined
    
    // Class 02 - No data found
    NO_DATA_FOUND           ("02", "000", Importance.DEBUG, null),
    
    // Class 07 - dynamic SQL error
        // SubClass 001 - using clause does not match dynamic parameter specifications
        // SubClass 002 - using clause does not match target specifications
        // SubClass 003 - cursor specification cannot be executed
        // SubClass 004 - using clause required for dynamic parameters
        // SubClass 005 - prepared statement not a cursor specification
        // SubClass 006 - restricted data type attribute violation
        // SubClass 007 - using clause required for result fields
        // SubClass 008 - invalid descriptor count
        // SubClass 009 - invalid descriptor index
        // SubClass 00B - data type transform function violation
        // SubClass 00C - undefined DATA value    
        // SubClass 00D - invalid DATA target
        // SubClass 00E - invalid LEVEL value
        // SubClass 00F - invalid DATETIME_INTERVAL_CODE
    // Class 08 - connection exception
        // SubClass 001 - SQL-client unable to establish SQL-connection
        // SubClass 002 - connection name in use
        // SubClass 003 - connection does not exist
        // SubClass 004 - SQL-server rejected establishment of SQL-connection
        // SubClass 006 - connection failure
        // SubClass 007 - transaction resolution unknown    
    // Class 09 - triggered action exception
    // Class 0A - feature not supported
    UNSUPPORTED_SQL         ("0A", "500", Importance.ERROR, UnsupportedSQLException.class),
    UNSUPPORTED_PARAMETERS  ("0A", "501", Importance.ERROR, UnsupportedParametersException.class),
    UNSUPPORTED_EXPLAIN     ("0A", "502", Importance.ERROR, UnsupportedExplainException.class),
    UNSUPPORTED_CREATE_SELECT ("0A", "503", Importance.ERROR, UnsupportedCreateSelectException.class),
    UNSUPPORTED_FK_INDEX    ("0A", "504", Importance.ERROR, UnsupportedFKIndexException.class),
    UNSUPPORTED_CHECK       ("0A", "505", Importance.ERROR, UnsupportedCheckConstraintException.class),
    UNSUPPORTED_GROUP_UNIQUE("0A", "506", Importance.DEBUG, UnsupportedUniqueGroupIndexException.class),
    UNSUPPORTED_INDEX_PREFIX("0A", "507", Importance.ERROR, UnsupportedIndexPrefixException.class),
    SELECT_EXISTS_ERROR     ("0A", "508", Importance.DEBUG, SelectExistsErrorException.class),
    UNSUPPORTED_GROUP_INDEX_JOIN("0A", "509", Importance.DEBUG, UnsupportedGroupIndexJoinTypeException.class),
    
    // Class 0D - invalid target type specification
    // Class 0E - invalid schema name list specification
    // Class 0F - locator exception
    // Class 0L - invalid grantor 
    // Class 0M - invalid SQL-invoked procedure reference
    // Class 0P - invalid role specification
    // Class 0S - invalid transform group name specification
    // Class 0T - target table disagrees with cursor definition
    // Class 0U - attempt to assign to non-updatable column
    // Class 0V - attempt to assign to ordering column
    // Class 0W - prohibited statement encountered during trigger execution
    // Class 0Z - diagnostics exceptions
    
    // Class 21 - cardinality violation
    // Class 22 - data exception
        // SubClass 001 - string data, right truncation
        // SubClass 002 - null value, no indicator parameter 
        // SubClass 003 - numeric value out of range
        // SubClass 004 - null value not allowed
        // SubClass 005 - error in assignment
        // SubClass 006 - invalid interval format
    INVALID_INTERVAL_FORMAT  ("22", "006", Importance.DEBUG, InvalidIntervalFormatException.class),
        // SubClass 007 - invalid datetime format 
    INVALID_DATE_FORMAT     ("22", "007", Importance.DEBUG, InvalidDateFormatException.class),
        // SubClass 008 - datetime field overflow 
        // SubClass 009 - invalid time zone displacement value

        // SubClass 00B - escape character conflict
        // SubClass 00C - invalid use of escape character
        // SubClass 00D - invalid escape octet
        // SubClass 00E - null value in array target
        // SubClass 00F - zero-length character string
    
        // SubClass 00G - most specific type mismatch
        // SubClass 00H - sequence generator limit exceeded    
        // SubClass 00P - interval value out of range
        // SubClass 00Q - multiset value overflow
    
        // SubClass 010 - invalid indicator parameter value    
        // SubClass 011 - substring error
        // SubClass 012 - division by zero
    DIVIDE_BY_ZERO          ("22", "012", Importance.DEBUG, DivisionByZeroException.class),
        // SubClass 013 - invalid preceding or following size in window function
    
        // SubClass 014 - invalid argument for NTILE function
        // SubClass 015 - interval field overflow 
        // SubClass 016 - invalid argument for NTH_VALUE function
        // SubClass 018 - invalid character value for cast
    INVALID_CHAR_TO_NUM     ("22", "018", Importance.DEBUG, InvalidCharToNumException.class),
        // SubClass 019 - invalid escape character
        // SubClass 01B - invalid regular expression
        // SubClass 01C - null row not permitted in table
    
        // SubClass 01E - invalid argument for natural logarithm
        // SubClass 01F - invalid argument for power function
        // SubClass 01G - invalid argument for width bucket function
        // SubClass 01H - invalid row version

        // SubClass 01U - attempt to replace a zero-length string
        // SubClass 01W - invalid row count in fetch first clause
        // SubClass 01X - invalid row count in result offset clause
    
        // SubClass 021 - character not in repertoire
        // SubClass 022 - indicator overflow 
        // SubClass 023 - invalid parameter value
    INVALID_PARAMETER_VALUE  ("22", "023", Importance.DEBUG, InvalidParameterValueException.class),
        // SubClass 024 - unterminated C string
        // SubClass 025 - invalid escape sequence
        // SubClass 026 - string data, length mismatch
        // SubClass 027 - trim error
    
        // SubClass 029 - noncharacter in UCS string
        // SubClass 02D - null value substituted for mutator subject parameter
    
        // SubClass 02E - array element error 
        // SubClass 02F - array data, right truncation    
        // SubClass 02G - invalid repeat argument in a sample clause
        // SubClass 02H - invalid sample size 02H
    TABLE_DEFINITION_CHANGED("22", "501", Importance.DEBUG, TableDefinitionChangedException.class),
    NEGATIVE_LIMIT          ("22", "502", Importance.DEBUG, NegativeLimitException.class),
    INVALID_ARGUMENT_TYPE   ("22", "503", Importance.DEBUG, InvalidArgumentTypeException.class),
    ZERO_DATE_TIME          ("22", "504", Importance.DEBUG, ZeroDateTimeException.class),

    // Class 23 - integrity constraint violation
    DUPLICATE_KEY           ("23", "501", Importance.DEBUG, DuplicateKeyException.class),
    FK_CONSTRAINT_VIOLATION ("23", "502", Importance.DEBUG, ForeignKeyConstraintDMLException.class),
    // Class 24 - invalid cursor state
    CURSOR_IS_FINISHED      ("24", "501", Importance.ERROR, CursorIsFinishedException.class), 
    CURSOR_IS_UNKNOWN       ("24", "502", Importance.ERROR, CursorIsUnknownException.class),
    NO_ACTIVE_CURSOR        ("24", "503", Importance.ERROR, NoActiveCursorException.class),
    CURSOR_CLOSE_BAD        ("24", "504", Importance.ERROR, CursorCloseBadException.class),
    

    // Class 25 - invalid transaction state
        // SubClass 001 - active SQL-transaction
        // SubClass 002 - branch transaction already active
    TRANSACTION_IN_PROGRESS ("25", "002", Importance.DEBUG, TransactionInProgressException.class),
        // SubClass 003 - inappropriate access mode for branch transaction
    TRANSACTION_READ_ONLY   ("25", "003", Importance.DEBUG, TransactionReadOnlyException.class),
        // SubClass 004 - inappropriate isolation level for branch transaction
        // SubClass 005 - no active SQL-transaction for branch transaction
    NO_TRANSACTION          ("25", "005", Importance.DEBUG, NoTransactionInProgressException.class),
        // SubClass 006 - read-only SQL-transaction
        // SubClass 007 - schema and data statement mixing not supported
        // SubClass 008 - held cursor requires same isolation level
    // Class 26 - invalid SQL statement name
    // Class 27 - triggered data change violation 
    // Class 28 - invalid authorization specification
    // Class 2C - invalid character set name 
    UNSUPPORTED_CHARSET     ("2C", "000", Importance.DEBUG, UnsupportedCharsetException.class),
    // Class 2D - invalid transaction termination
    // Class 2E - invalid connection name
    // Class 2F - SQL routine exception
    // Class 2H - invalid collation name
    
    
    // Class 30 - invalid SQL statement identifier
    // Class 33 - invalid SQL descriptor name
    // Class 34 - invalid cursor name
    // Class 35 - invalid condition number
    // Class 36 - cursor sensitivity exception
    // Class 38 - external routine exception
    STALE_PLAN              ("38", "000", Importance.ERROR, StalePlanException.class),
    // Class 39 - external routine invocation
    // Class 3B - savepoint exception
    // Class 3C - ambiguous cursor name
    // Class 3D - invalid catalog name
    // Class 3F - invalid schema name
    NO_SUCH_SCHEMA          ("3F", "000", Importance.DEBUG, NoSuchSchemaException.class),
    
    // Class 40 - transaction rollback
    QUERY_TIMEOUT           ("40", "000", Importance.ERROR, QueryTimedOutException.class),

    // Class 42 - syntax error or access rule violation
    // These exceptions are re-thrown errors from the parser and from the
    // AISBinder, ASTStatementLoader, BasicDDLFunctions, or BasicDMLFunctions
    PARSE_EXCEPTION         ("42", "000", Importance.DEBUG, ParseException.class),
    NO_SUCH_TABLE           ("42", "501", Importance.DEBUG, NoSuchTableException.class), 
    NO_INDEX                ("42", "502", Importance.DEBUG, NoSuchIndexException.class),
    NO_SUCH_GROUP           ("42", "503", Importance.DEBUG, NoSuchGroupException.class), 
    NO_SUCH_TABLEDEF        ("42", "504", Importance.DEBUG, RowDefNotFoundException.class), 
    NO_SUCH_TABLEID         ("42", "505", Importance.DEBUG, NoSuchTableIdException.class),
    AMBIGUOUS_COLUMN_NAME   ("42", "506", Importance.DEBUG, AmbiguousColumNameException.class),
    SUBQUERY_RESULT_FAIL    ("42", "507", Importance.DEBUG, SubqueryResultsSetupException.class),
    JOIN_NODE_ERROR         ("42", "508", Importance.DEBUG, JoinNodeAdditionException.class),
    MULTIPLE_JOINS          ("42", "509", Importance.DEBUG, MultipleJoinsToTableException.class),
    VIEW_BAD_SUBQUERY       ("42", "50A", Importance.DEBUG, ViewHasBadSubqueryException.class),
    TABLE_BAD_SUBQUERY      ("42", "50B", Importance.DEBUG, TableIsBadSubqueryException.class),
    WRONG_FUNCTION_ARITY    ("42", "50C", Importance.DEBUG, WrongExpressionArityException.class),
    NO_SUCH_FUNCTION        ("42", "50D", Importance.DEBUG, NoSuchFunctionException.class),
    ORDER_GROUP_BY_NON_INTEGER_CONSTANT("42", "50E", Importance.DEBUG, OrderGroupByNonIntegerConstant.class),
    ORDER_GROUP_BY_INTEGER_OUT_OF_RANGE("42", "50F", Importance.DEBUG, OrderGroupByIntegerOutOfRange.class),
    MISSING_GROUP_INDEX_JOIN("42", "510", Importance.DEBUG, MissingGroupIndexJoinTypeException.class),
    TABLE_INDEX_JOIN        ("42", "511", Importance.DEBUG, TableIndexJoinTypeException.class),
    INSERT_WRONG_COUNT      ("42", "512", Importance.DEBUG, InsertWrongCountException.class),
    UNSUPPORTED_CONFIGURATION ("42", "513", Importance.DEBUG, UnsupportedConfigurationException.class),

    // Class 44 - with check option violation
    

    // Class 50 - DDL definition failure
    PROTECTED_TABLE         ("50", "002", Importance.DEBUG, ProtectedTableDDLException.class), 
    JOIN_TO_PROTECTED_TABLE ("50", "003", Importance.DEBUG, JoinToProtectedTableException.class), 
    JOIN_TO_UNKNOWN_TABLE   ("50", "004", Importance.DEBUG, JoinToUnknownTableException.class),  
    JOIN_TO_WRONG_COLUMNS   ("50", "005", Importance.DEBUG, JoinToWrongColumnsException.class), 
    DUPLICATE_TABLE         ("50", "006", Importance.DEBUG, DuplicateTableNameException.class), 
    UNSUPPORTED_DROP        ("50", "007", Importance.DEBUG, UnsupportedDropException.class),
    UNSUPPORTED_DATA_TYPE   ("50", "008", Importance.DEBUG, UnsupportedDataTypeException.class),
    JOIN_TO_MULTIPLE_PARENTS("50", "009", Importance.DEBUG, JoinToMultipleParentsException.class), 
    UNSUPPORTED_INDEX_DATA_TYPE("50", "00A", Importance.DEBUG, UnsupportedIndexDataTypeException.class),
    UNSUPPORTED_INDEX_SIZE  ("50", "00B", Importance.DEBUG, UnsupportedIndexSizeException.class),
    DUPLICATE_COLUMN        ("50", "00C", Importance.DEBUG, DuplicateColumnNameException.class),
    DUPLICATE_GROUP         ("50", "00D", Importance.DEBUG, DuplicateGroupNameException.class), 
    REFERENCED_TABLE        ("50", "00E", Importance.DEBUG, ReferencedTableException.class),  
    DROP_INDEX_NOT_ALLOWED  ("50", "00F", Importance.DEBUG, DropIndexNotAllowedException.class),
    FK_DDL_VIOLATION        ("50", "00G", Importance.DEBUG, ForeignConstraintDDLException.class),
    PROTECTED_INDEX         ("50", "00H", Importance.DEBUG, ProtectedIndexException.class),
    BRANCHING_GROUP_INDEX   ("50", "00I", Importance.DEBUG, BranchingGroupIndexException.class),
    WRONG_NAME_FORMAT       ("50", "00J", Importance.DEBUG, WrongNameFormatException.class),
    DUPLICATE_VIEW          ("50", "00K", Importance.DEBUG, DuplicateViewException.class),
    UNDEFINED_VIEW          ("50", "00L", Importance.DEBUG, UndefinedViewException.class),
    SUBQUERY_ONE_COLUMN     ("50", "00M", Importance.DEBUG, SubqueryOneColumnException.class),
    DUPLICATE_SCHEMA        ("50", "00N", Importance.DEBUG, DuplicateSchemaException.class),
    DROP_SCHEMA_NOT_ALLOWED ("50", "00O", Importance.DEBUG, DropSchemaNotAllowedException.class),
    WRONG_TABLE_FOR_INDEX   ("50", "00P", Importance.DEBUG, WrongTableForIndexException.class),
    MISSING_DDL_PARAMETERS  ("50", "00Q", Importance.DEBUG, MissingDDLParametersException.class),
    INDEX_COL_NOT_IN_GROUP  ("50", "00R", Importance.DEBUG, IndexColNotInGroupException.class),
    INDEX_TABLE_NOT_IN_GROUP("50", "00S", Importance.DEBUG, IndexTableNotInGroupException.class),
    INDISTINGUISHABLE_INDEX ("50", "00T", Importance.DEBUG, IndistinguishableIndexException.class),
    // AIS Validation errors, Attempts to modify and build an AIS failed
    // due to missing or invalid information.
    GROUP_MULTIPLE_ROOTS    ("50", "010", Importance.DEBUG, GroupHasMultipleRootsException.class),
    JOIN_TYPE_MISMATCH      ("50", "011", Importance.DEBUG, JoinColumnTypesMismatchException.class),
    PK_NULL_COLUMN          ("50", "012", Importance.DEBUG, PrimaryKeyNullColumnException.class),
    DUPLICATE_INDEXES       ("50", "013", Importance.DEBUG, DuplicateIndexException.class),
    MISSING_PRIMARY_KEY     ("50", "014", Importance.DEBUG, NoPrimaryKeyException.class),
    DUPLICATE_TABLEID       ("50", "015", Importance.DEBUG, DuplicateTableIdException.class),
    JOIN_COLUMN_MISMATCH    ("50", "016", Importance.DEBUG, JoinColumnMismatchException.class),
    INDEX_LACKS_COLUMNS     ("50", "017", Importance.DEBUG, IndexLacksColumnsException.class),
    NO_SUCH_COLUMN          ("50", "018", Importance.DEBUG, NoSuchColumnException.class),
    DUPLICATE_INDEX_TREENAME("50", "019", Importance.DEBUG, DuplicateIndexTreeNamesException.class),
    DUPLICATE_TABLE_TREENAME("50", "01A", Importance.DEBUG, DuplicateTableTreeNamesException.class),
    TABLE_NOT_IN_GROUP      ("50", "01B", Importance.DEBUG, TableNotInGroupException.class),
    NAME_IS_NULL            ("50", "01C", Importance.DEBUG, NameIsNullException.class),
    DUPLICATE_INDEX_COLUMN  ("50", "01D", Importance.DEBUG, DuplicateIndexColumnException.class),
    COLUMN_POS_ORDERED      ("50", "01E", Importance.DEBUG, ColumnPositionNotOrderedException.class),
    TABLE_COL_IN_GROUP      ("50", "01F", Importance.DEBUG, TableColumnNotInGroupException.class),
    GROUP_MISSING_COL       ("50", "01G", Importance.DEBUG, GroupMissingTableColumnException.class),
    GROUP_MISSING_INDEX     ("50", "01H", Importance.DEBUG, GroupMissingIndexException.class),
    TREENAME_MISMATCH       ("50", "01I", Importance.DEBUG, TreeNameMismatchException.class),
    NULL_REFERENCE          ("50", "01J", Importance.DEBUG, AISNullReferenceException.class),
    BAD_AIS_REFERENCE       ("50", "01L", Importance.DEBUG, BadAISReferenceException.class),
    BAD_INTERNAL_SETTING    ("50", "01M", Importance.DEBUG, BadAISInternalSettingException.class),
    TYPES_ARE_STATIC        ("50", "01N", Importance.DEBUG, TypesAreStaticException.class),
    GROUP_INDEX_DEPTH       ("50", "01O", Importance.DEBUG, GroupIndexDepthException.class),
    DUPLICATE_INDEXID       ("50", "01P", Importance.DEBUG, DuplicateIndexIdException.class),

    // Class 51 - Internal problems created by user configuration
    STALE_AIS               ("51", "001", Importance.TRACE, OldAISException.class),
    METAMODEL_MISMATCH      ("51", "002", Importance.ERROR, MetaModelVersionMismatchException.class),
    // Messaging errors
    MALFORMED_REQUEST       ("51", "010", Importance.ERROR, null), 
    BAD_STATISTICS_TYPE     ("51", "011", Importance.ERROR, BadStatisticsTypeException.class),
    
    // Class 52 - Configuration & startup errors
    SERVICE_NOT_STARTED     ("52", "001", Importance.ERROR, ServiceNotStartedException.class),
    SERVICE_ALREADY_STARTED ("52", "002", Importance.ERROR, ServiceStartupException.class),
    SERVICE_CIRC_DEPEND     ("52", "003", Importance.ERROR, CircularDependencyException.class),
    BAD_ADMIN_DIRECTORY     ("52", "004", Importance.ERROR, BadAdminDirectoryException.class),
    ZOOKEEPER_INIT_FAIL     ("52", "005", Importance.ERROR, ZooKeeperInitFailureException.class),
    CONFIG_LOAD_FAILED      ("52", "006", Importance.ERROR, ConfigurationPropertiesLoadException.class),
    THREAD_START_INTR       ("52", "007", Importance.ERROR, ThreadStartInterruptedException.class),
    THREAD_STOP_INTR        ("52", "008", Importance.DEBUG, ThreadStopInterruptedException.class),
    NET_START_IO_ERROR      ("52", "009", Importance.ERROR, NetworkStartIOException.class),
    NET_STOP_IO_ERROR       ("52", "00A", Importance.ERROR, NetworkStopIOException.class),
    TAP_BEAN_FAIL           ("52", "00B", Importance.ERROR, TapBeanFailureException.class),
    SET_FILTER_FAIL         ("52", "00C", Importance.ERROR, DisplayFilterSetException.class),
    SCHEMA_LOAD_IO_ERROR    ("52", "00D", Importance.ERROR, SchemaLoadIOException.class),
    QUERY_LOG_CLOSE_FAIL    ("52", "00E", Importance.ERROR, QueryLogCloseException.class),
    INVALID_PORT            ("52", "00F", Importance.ERROR, InvalidPortException.class), 
    INVALID_VOLUME          ("52", "010", Importance.ERROR, InvalidVolumeException.class),

    // Class 53 - Internal error 
    INTERNAL_ERROR          ("53", "000", Importance.ERROR, null),
    INTERNAL_CORRUPTION     ("53", "001", Importance.ERROR, RowDataCorruptionException.class),
    AIS_TOO_LARGE           ("53", "002", Importance.ERROR, AISTooLargeException.class),
    PERSISTIT_ERROR         ("53", "003", Importance.ERROR, PersistitAdapterException.class),
    TABLE_NOT_BOUND         ("53", "004", Importance.ERROR, TableNotBoundException.class),
    ROW_OUTPUT              ("53", "005", Importance.DEBUG, RowOutputException.class),    
    SCAN_RETRY_ABANDONDED   ("53", "006", Importance.ERROR, ScanRetryAbandonedException.class),
    AIS_MYSQL_SQL_EXCEPTION ("53", "007", Importance.DEBUG, AisSQLErrorException.class),
    AIS_CSV_ERROR           ("53", "008", Importance.DEBUG, AisCSVErrorException.class),
    TABLEDEF_MISMATCH       ("53", "009", Importance.DEBUG, TableDefinitionMismatchException.class), 
    
    // Class 55 - Type conversion errors
    UNKNOWN_TYPE            ("55", "001", Importance.DEBUG, UnknownDataTypeException.class),    
    UNKNOWN_TYPE_SIZE       ("55", "002", Importance.DEBUG, UnknownTypeSizeException.class),
    INCONVERTIBLE_TYPES     ("55", "003", Importance.DEBUG, InconvertibleTypesException.class),
    OVERFLOW                ("55", "004", Importance.DEBUG, OverflowException.class),
    
    // Class 56 - Explain query errors
    UNABLE_TO_EXPLAIN       ("56", "000", Importance.DEBUG, UnableToExplainException.class),

    // Class 57 - Insert, Update, Delete processing exceptions
    NO_SUCH_ROW             ("57", "001", Importance.DEBUG, NoSuchRowException.class),
    CONCURRENT_MODIFICATION ("57", "002", Importance.DEBUG, ConcurrentScanAndUpdateException.class),
    NO_ROWS_UPDATED         ("57", "003", Importance.DEBUG, NoRowsUpdatedException.class),    
    TOO_MANY_ROWS_UPDATED   ("57", "004", Importance.DEBUG, TooManyRowsUpdatedException.class),  
    INSERT_NULL_CHECK       ("57", "005", Importance.DEBUG, InsertNullCheckFailedException.class),

    // Class 58 - Query canceled by user
    QUERY_CANCELED          ("58", "000", Importance.ERROR, QueryCanceledException.class),    
    // Class 70 - Unknown errors 
    UNKNOWN                 ("70", "000", Importance.ERROR, null),
    UNEXPECTED_EXCEPTION    ("70", "001", Importance.ERROR, null),
    UNSUPPORTED_OPERATION   ("70", "002", Importance.ERROR, null),
    UNSUPPORTED_MODIFICATION("70", "003", Importance.DEBUG, UnsupportedModificationException.class),
   
    // Generic codes - all unused. 
    // AkSserver and Head are out of sync
    //SERVER_SHUTDOWN         (1, 0, Importance.DEBUG, null),
    // DML errors
    // AkSserver errors
    //MULTIGENERATIONAL_TABLE(30, 900, Importance.ERROR, null),
    ;

    private final String code;
    private final String subcode; 
    
    private final Importance importance;
    private final Class<? extends InvalidOperationException> exceptionClass;
    private String formattedValue; 
    private static ResourceBundle resourceBundle = ResourceBundle.getBundle("com.akiban.server.error.error_code");

    private ErrorCode(String code, String subCode, Importance importance, 
            Class<? extends InvalidOperationException> exception) {
        this.code = code;
        this.subcode = subCode;
        this.importance = importance;
        this.exceptionClass = exception;
        this.formattedValue = this.code + this.subcode; 
    }

    public static ErrorCode valueOfCode(String value)
    {
        for (ErrorCode e : values()) {
            if (e.getFormattedValue().equals(value)) {
                return e;
            }
        }
        throw new RuntimeException(String.format("Invalid code value: %s", value));
    }

    public Importance getImportance() {
        return importance;
    }
    
    public String getMessage() { 
        return resourceBundle.getString(name());
    }
    public Class<? extends InvalidOperationException> associatedExceptionClass() {
        return exceptionClass; 
    }
    public String getFormattedValue() {
        return formattedValue;
    }
    
    public String getCode() { 
        return code;
    }
    
    public String getSubCode() {
        return subcode;
    }

    public static enum Importance {
        TRACE,
        DEBUG,
        ERROR
    }
}
