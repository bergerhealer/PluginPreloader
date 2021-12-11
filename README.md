# Plugin Preloader
This is a simple plugin preloader library, consisting of a single Java class. Shade this class in your plugin,
and refer to is as your main entrypoint. Next, add a preloader section to your plugin.yml, like as follows:
```yaml
main: com.bergerkiller.bukkit.mw.Preloader
preloader:
    main: com.bergerkiller.bukkit.mw.MyWorlds
    hastebinServer: https://paste.traincarts.net
    commands: [world, myworlds]
    depend:
        BKCommonLib: https://www.spigotmc.org/resources/bkcommonlib.39590/
```

### Features
- Checks that all dependencies are actually enabled
- Handles errors during plugin main class construction/onLoad()
- Remains functional on failure to inform people ingame that the plugin could not be enabled, and why
  - Installs an event listener to tell ops about it on join
  - Tells players when they use the plugin's main commands
- More gracefully handles missing dependencies so your Discord isn't spammed by people that forgot to install it
- Completely transparent when there is no problem. Swaps out the preloader main plugin instance with your actual one.
- Native support integration with [BKCommonLib](https://github.com/bergerhealer/BKCommonLib) and its PluginBase API

### Usage for Maven
You will probably need to add the repository:
```xml
    <repository>
        <id>MG-Dev Jenkins CI Maven Repository</id>
        <url>https://ci.mg-dev.eu/plugin/repository/everything</url>
    </repository>
```

Then add the dependency:
```xml
    <dependency>
        <groupId>com.bergerkiller.bukkit.preloader</groupId>
        <artifactId>PluginPreloader</artifactId>
        <version>1.5</version>
        <scope>compile</scope>
    </dependency>
```

Then finally, shade it into your plugin's jar file at a custom package using maven shade plugin:
```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.2.3</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
          </execution>
        </executions>
        <configuration>
          <createDependencyReducedPom>true</createDependencyReducedPom>
          <relocations>
            <relocation>
              <pattern>com.bergerkiller.bukkit.preloader</pattern>
              <shadedPattern>com.bergerkiller.bukkit.mw</shadedPattern>
            </relocation>
          </relocations>
          <artifactSet>
            <includes>
              <include>com.bergerkiller.bukkit.preloader</include>
            </includes>
          </artifactSet>
        </configuration>
      </plugin>
```

If you're shading other things in too you might want to leave createDependencyReducedPom on false instead.