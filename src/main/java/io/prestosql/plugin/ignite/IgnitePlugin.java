package io.prestosql.plugin.ignite;

import io.prestosql.plugin.jdbc.JdbcPlugin;

public class IgnitePlugin extends JdbcPlugin
{
    public IgnitePlugin()
    {
        super("ignite", new IgniteClientModule());
    }
}
