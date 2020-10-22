package io.prestosql.plugin.ignite;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;

import java.sql.SQLException;
import java.util.Properties;

import org.apache.ignite.IgniteJdbcThinDriver;

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;

import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.prestosql.plugin.jdbc.BaseJdbcConfig;
import io.prestosql.plugin.jdbc.ConnectionFactory;
import io.prestosql.plugin.jdbc.DecimalConfig;
import io.prestosql.plugin.jdbc.DecimalSessionPropertiesProvider;
import io.prestosql.plugin.jdbc.DriverConnectionFactory;
import io.prestosql.plugin.jdbc.ForBaseJdbc;
import io.prestosql.plugin.jdbc.JdbcClient;
import io.prestosql.plugin.jdbc.SessionPropertiesProvider;
import io.prestosql.plugin.jdbc.TypeHandlingJdbcConfig;
import io.prestosql.plugin.jdbc.credential.CredentialProvider;
//import io.prestosql.plugin.mysql.MySqlConfig;

public class IgniteClientModule extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(IgniteClient.class).in(Scopes.SINGLETON);
        ensureConnectStringValid(buildConfigObject(BaseJdbcConfig.class).getConnectionUrl());
        configBinder(binder).bindConfig(TypeHandlingJdbcConfig.class);
        configBinder(binder).bindConfig(IgniteConfig.class);
        configBinder(binder).bindConfig(DecimalConfig.class);
        newSetBinder(binder, SessionPropertiesProvider.class).addBinding().to(DecimalSessionPropertiesProvider.class).in(Scopes.SINGLETON);
    }

    private static void ensureConnectStringValid(String connectionUrl)
    {
        try {
            //Driver driver = new Driver();
            IgniteJdbcThinDriver driver = new IgniteJdbcThinDriver();
        	driver.acceptsURL(connectionUrl);
            boolean urlProperties = driver.acceptsURL(connectionUrl);
            checkArgument(urlProperties, "Invalid JDBC URL for Ignite connector");
            //checkArgument(driver.database(urlProperties) == null, "Database (catalog) must not be specified in JDBC URL for MySQL connector");
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    public static ConnectionFactory createConnectionFactory(BaseJdbcConfig config, CredentialProvider credentialProvider,IgniteConfig igniteConfig)
            throws SQLException
    {
        Properties connectionProperties = new Properties(); 
        connectionProperties.setProperty("streaming","true");
        connectionProperties.setProperty("user",igniteConfig.getUser());
        connectionProperties.setProperty("password",igniteConfig.getPassword());

        return new DriverConnectionFactory(
                new IgniteJdbcThinDriver(),
                config.getConnectionUrl(),
                connectionProperties,
                credentialProvider);
    }
}