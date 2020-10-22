package io.prestosql.plugin.ignite;

import static com.fasterxml.jackson.core.JsonFactory.Feature.CANONICALIZE_FIELD_NAMES;
import static com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Verify.verify;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.slice.Slices.utf8Slice;
import static io.prestosql.plugin.jdbc.ColumnMapping.DISABLE_PUSHDOWN;
import static io.prestosql.plugin.jdbc.DecimalConfig.DecimalMapping.ALLOW_OVERFLOW;
import static io.prestosql.plugin.jdbc.DecimalSessionPropertiesProvider.getDecimalDefaultScale;
import static io.prestosql.plugin.jdbc.DecimalSessionPropertiesProvider.getDecimalRounding;
import static io.prestosql.plugin.jdbc.DecimalSessionPropertiesProvider.getDecimalRoundingMode;
import static io.prestosql.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.prestosql.plugin.jdbc.StandardColumnMappings.decimalColumnMapping;
import static io.prestosql.plugin.jdbc.StandardColumnMappings.varcharWriteFunction;
import static io.prestosql.plugin.jdbc.TypeHandlingJdbcPropertiesProvider.getUnsupportedTypeHandling;
import static io.prestosql.plugin.jdbc.UnsupportedTypeHandling.IGNORE;
import static io.prestosql.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.sql.DatabaseMetaData.columnNoNulls;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.function.BiFunction;

import javax.inject.Inject;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import io.airlift.json.ObjectMapperProvider;
import io.airlift.log.Logger;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import io.prestosql.plugin.jdbc.BaseJdbcClient;
import io.prestosql.plugin.jdbc.BaseJdbcConfig;
import io.prestosql.plugin.jdbc.ColumnMapping;
import io.prestosql.plugin.jdbc.ConnectionFactory;
import io.prestosql.plugin.jdbc.JdbcColumnHandle;
import io.prestosql.plugin.jdbc.JdbcIdentity;
import io.prestosql.plugin.jdbc.JdbcTableHandle;
import io.prestosql.plugin.jdbc.JdbcTypeHandle;
import io.prestosql.plugin.jdbc.UnsupportedTypeHandling;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.StandardTypes;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeManager;
import io.prestosql.spi.type.TypeSignature;

public class IgniteClient extends BaseJdbcClient
{
	private static final Logger log = Logger.get(IgniteClient.class);
    private final Type jsonType;

    @Inject
    public IgniteClient(BaseJdbcConfig config, ConnectionFactory connectionFactory, TypeManager typeManager)
    {
        super(config, "`", connectionFactory);
        this.jsonType = typeManager.getType(new TypeSignature(StandardTypes.JSON));
    }


    @Override
    protected Collection<String> listSchemas(Connection connection)
    {
        try (ResultSet resultSet = connection.getMetaData().getSchemas(connection.getCatalog(), null)) {
            ImmutableSet.Builder<String> schemaNames = ImmutableSet.builder();
            while (resultSet.next()) {
                String schemaName = resultSet.getString("TABLE_SCHEM");
                // skip internal schemas
                System.out.println("schemaName ->"+schemaName);
                if (!schemaName.trim().equalsIgnoreCase("information_schema")) {
                    schemaNames.add(schemaName);
                }
            }
            return schemaNames.build();
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
    }
    
    @Override
    public void abortReadConnection(Connection connection)
            throws SQLException
    {
        // Abort connection before closing. Without this, the ignite driver
        // attempts to drain the connection by reading all the results.
        connection.abort(directExecutor());
    }

    @Override
    public PreparedStatement getPreparedStatement(Connection connection, String sql)
            throws SQLException
    {
        PreparedStatement statement = connection.prepareStatement(sql);
        return statement;
    }

    protected static ResultSet getColumns(JdbcTableHandle tableHandle, DatabaseMetaData metadata)
            throws SQLException
    {
        Optional<String> escape = Optional.ofNullable(metadata.getSearchStringEscape());
        return metadata.getColumns(
                null,
                escapeNamePatternTable(Optional.ofNullable(tableHandle.getSchemaName()), escape).orElse(null),
                escapeNamePatternTable(Optional.ofNullable(tableHandle.getTableName()), escape).orElse(null),
                null);
    }
    
    public List<JdbcColumnHandle> getColumns(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        try (Connection connection = connectionFactory.openConnection(JdbcIdentity.from(session))) {
        	try (ResultSet resultSet = getColumns(tableHandle, connection.getMetaData())) {
            	int allColumns = 0;
                List<JdbcColumnHandle> columns = new ArrayList<>();
                while (resultSet.next()) {
                	allColumns++;
                	int COLUMN_SIZE=resultSet.getInt("COLUMN_SIZE");
                	String columnName = resultSet.getString("COLUMN_NAME");
                	if (resultSet.getInt("COLUMN_SIZE")==0){
                		COLUMN_SIZE=2147483647;
                	}
                	
                	JdbcTypeHandle typeHandle = new JdbcTypeHandle(
                            resultSet.getInt("DATA_TYPE"),
                            Optional.ofNullable(resultSet.getString("TYPE_NAME")),
                            COLUMN_SIZE,
                            resultSet.getInt("DECIMAL_DIGITS"),
                            Optional.empty());

                    Optional<ColumnMapping> columnMapping = toPrestoType(session, connection, typeHandle);
                    log.debug("Mapping data type of '%s' column '%s': %s mapped to %s", tableHandle.getSchemaTableName(), columnName, typeHandle, columnMapping);
                    // skip unsupported column types
                    boolean nullable = (resultSet.getInt("NULLABLE") != columnNoNulls);
                    // Note: some databases (e.g. SQL Server) do not return column remarks/comment here.
                    Optional<String> comment = Optional.ofNullable(emptyToNull(resultSet.getString("REMARKS")));
                    
                    // skip unsupported column types
                    if (columnMapping.isPresent()) {
                    	columns.add(JdbcColumnHandle.builder()
                                .setColumnName(columnName)
                                .setJdbcTypeHandle(typeHandle)
                                .setColumnType(columnMapping.get().getType())
                                .setNullable(nullable)
                                .setComment(comment)
                                .build());
                    
                    }
                    if (!columnMapping.isPresent()) {
                        UnsupportedTypeHandling unsupportedTypeHandling = getUnsupportedTypeHandling(session);
                        verify(unsupportedTypeHandling == IGNORE, "Unsupported type handling is set to %s, but toPrestoType() returned empty", unsupportedTypeHandling);
                    }
                }
                if (columns.isEmpty()) {
                    // A table may have no supported columns. In rare cases (e.g. PostgreSQL) a table might have no columns at all.
                    throw new TableNotFoundException(
                            tableHandle.getSchemaTableName(),
                            format("Table '%s' has no supported columns (all %s columns are not supported)", tableHandle.getSchemaTableName(), allColumns));
                
                }
                return ImmutableList.copyOf(columns);
            }
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
    }
    
    
    
    @Override
    protected ResultSet getTables(Connection connection, Optional<String> schemaName, Optional<String> tableName)
            throws SQLException
    {
   
        DatabaseMetaData metadata = connection.getMetaData();
        Optional<String> escape = Optional.ofNullable(metadata.getSearchStringEscape());
        return metadata.getTables(
                connection.getCatalog(),
                escapeNamePatternTable(schemaName, escape).orElse(null),
                escapeNamePatternTable(tableName, escape).orElse(null),
                new String[] {"TABLE","TABLE_NAME","VIEW","TABLE_SCHEM"});
    }

    protected static Optional<String> escapeNamePatternTable(Optional<String> name, Optional<String> escape)
    {
        if (!name.isPresent() || !escape.isPresent()) {
            return name;
        }
        return Optional.of(escapeNamePatternTable(name.get(), escape.get()));
    }

    private static String escapeNamePatternTable(String name, String escape)
    {
        requireNonNull(name, "name is null");
        requireNonNull(escape, "escape is null");
        checkArgument(!escape.equals("_"), "Escape string must not be '_'");
        checkArgument(!escape.equals("%"), "Escape string must not be '%'");
        name = name.replace(escape, escape + escape);
        name = name.replace("%", escape + "%");
        return name;
        
    }
    
   // @Override
   // protected String getTableSchemaName(ResultSet resultSet)
   //         throws SQLException
   // {
   //     return resultSet.getString("TABLE_SCHEM");
   // }

    @Override
    public Optional<ColumnMapping> toPrestoType(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        String jdbcTypeName = typeHandle.getJdbcTypeName()
                .orElseThrow(() -> new PrestoException(JDBC_ERROR, "Type name is missing: " + typeHandle));

        Optional<ColumnMapping> mapping = getForcedMappingToVarchar(typeHandle);
        if (mapping.isPresent()) {
            return mapping;
        }
        if (jdbcTypeName.equalsIgnoreCase("json")) {
            return Optional.of(jsonColumnMapping());
        }
        if (typeHandle.getJdbcType() == Types.DECIMAL && getDecimalRounding(session) == ALLOW_OVERFLOW) {
            int precision = typeHandle.getColumnSize();
            if (precision > Decimals.MAX_PRECISION) {
                int scale = min(typeHandle.getDecimalDigits(), getDecimalDefaultScale(session));
                return Optional.of(decimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, scale), getDecimalRoundingMode(session)));
            }
        }
        return super.toPrestoType(session, connection, typeHandle);
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        try {
            createTable(session, tableMetadata, tableMetadata.getTable().getTableName());
        }
        catch (SQLException e) {
            //boolean exists = SQL_STATE_ER_TABLE_EXISTS_ERROR.equals(e.getSQLState());
            throw new PrestoException(e.getMessage().contains("Table already exists") ? ALREADY_EXISTS : JDBC_ERROR, e);
        }
    }

    @Override
    public void renameColumn(JdbcIdentity identity, JdbcTableHandle handle, JdbcColumnHandle jdbcColumn, String newColumnName)
    {
        try (Connection connection = connectionFactory.openConnection(identity)) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (metadata.storesUpperCaseIdentifiers()) {
                newColumnName = newColumnName.toUpperCase(ENGLISH);
            }
            String sql = format(
                    "ALTER TABLE %s RENAME COLUMN %s TO %s",
                    quoted(handle.getCatalogName(), handle.getSchemaName(), handle.getTableName()),
                    quoted(jdbcColumn.getColumnName()),
                    quoted(newColumnName));
            execute(connection, sql);
        }
        catch (SQLException e) {
            // MySQL versions earlier than 8 do not support the above RENAME COLUMN syntax
            //if (SQL_STATE_SYNTAX_ERROR.equals(e.getSQLState())) {
              //  throw new PrestoException(NOT_SUPPORTED, format("Rename column not supported in catalog: '%s'", handle.getCatalogName()), e);
           // }
            throw new PrestoException(JDBC_ERROR, e);
        }
    }

    @Override
    protected void copyTableSchema(Connection connection, String catalogName, String schemaName, String tableName, String newTableName, List<String> columnNames)
            throws SQLException
    {
    	List<String> pkColumn=new ArrayList<String>();
    	Map<String, String> columnDetails=new HashMap<String,String>();
    	ResultSet columMetadata=connection.getMetaData().getColumns(catalogName,schemaName, tableName,null);
    	while (columMetadata.next()) {
    		columnDetails.put(columMetadata.getString("COLUMN_NAME"), columMetadata.getString("TYPE_NAME"));
		}
    	
    	ResultSet primaryKey=connection.getMetaData().getPrimaryKeys(catalogName, schemaName, tableName);
    	
    	while (primaryKey.next()) {
    		pkColumn.add(primaryKey.getString("PK_NAME"));
			
		}
    	StringBuilder primaryKeyString=new StringBuilder("PRIMARY KEY (");
    	StringBuilder columnDetailsString=new StringBuilder();
    	for (String pk : pkColumn) {
    		primaryKeyString.append(pk).append(",");
		}
    	
    	for (Entry<String, String> ent : columnDetails.entrySet()) {
    		columnDetailsString.append(ent.getKey()).append(" ").append(ent.getValue()).append(",");
		}
    	
    	primaryKeyString.replace(primaryKeyString.lastIndexOf(","),primaryKeyString.length(),")");
    	columnDetailsString.append(primaryKeyString.toString());
    	String createTableSql="CREATE TABLE "+quoted(catalogName, schemaName, newTableName)+" ("+ columnDetailsString.toString()+")";
//        String sql = format(
//                "CREATE TABLE %s LIKE %s",
//                quoted(catalogName, schemaName, newTableName),
//                quoted(catalogName, schemaName, tableName));
        execute(connection, createTableSql);
    }

    @Override
    public void renameTable(JdbcIdentity identity, JdbcTableHandle handle, SchemaTableName newTableName)
    {
        // MySQL doesn't support specifying the catalog name in a rename. By setting the
        // catalogName parameter to null, it will be omitted in the ALTER TABLE statement.
        verify(handle.getSchemaName() == null);
        renameTable(identity, null, handle.getCatalogName(), handle.getTableName(), newTableName);
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> sql + " LIMIT " + limit);
    }

    @Override
    public boolean isLimitGuaranteed()
    {
        return true;
    }

    private ColumnMapping jsonColumnMapping()
    {
        return ColumnMapping.sliceMapping(
                jsonType,
                (resultSet, columnIndex) -> jsonParse(utf8Slice(resultSet.getString(columnIndex))),
                varcharWriteFunction(),
                DISABLE_PUSHDOWN);
    }

    @SuppressWarnings("deprecation")
	private static final JsonFactory JSON_FACTORY = new JsonFactory()
            .disable(CANONICALIZE_FIELD_NAMES);

    private static final ObjectMapper SORTED_MAPPER = new ObjectMapperProvider().get().configure(ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static Slice jsonParse(Slice slice)
    {
        try (JsonParser parser = createJsonParser(slice)) {
            byte[] in = slice.getBytes();
            SliceOutput dynamicSliceOutput = new DynamicSliceOutput(in.length);
            SORTED_MAPPER.writeValue((OutputStream) dynamicSliceOutput, SORTED_MAPPER.readValue(parser, Object.class));
            // nextToken() returns null if the input is parsed correctly,
            // but will throw an exception if there are trailing characters.
            parser.nextToken();
            return dynamicSliceOutput.slice();
        }
        catch (Exception e) {
            throw new PrestoException(INVALID_FUNCTION_ARGUMENT, format("Cannot convert '%s' to JSON", slice.toStringUtf8()));
        }
    }

    private static JsonParser createJsonParser(Slice json)
            throws IOException
    {
        // Jackson tries to detect the character encoding automatically when using InputStream
        // so we pass an InputStreamReader instead.
        return JSON_FACTORY.createParser(new InputStreamReader(json.getInput(), UTF_8));
    }
}
