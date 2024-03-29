/*
 * Feel free to use in this in your own plugin, I do not care.
 * For those that do care:
 *
 * MIT License
 * Copyright (c) 2021 TeamBergerHealer
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.bergerkiller.bukkit.preloader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

import com.google.common.collect.ImmutableList;

/**
 * A simple class that uses the 'preloader' section in the plugin.yml
 * to load the actual plugin. If dependencies declared in this section
 * are not available or other errors occur, the plugin isn't loaded.<br>
 * <br>
 * If the plugin couldn't be loaded, a message is sent to operators when
 * they join the server, as well as when executing the plugin's command.<br>
 * <br>
 * In case commands are registered inside onEnable() rather than as part
 * of plugin.yml, commands can be declared inside the preloader section.
 * These will be registered if the plugin could not be loaded.<br>
 * <br>
 * Unlike the normal Bukkit PluginLoader, if the
 * {@link JavaPlugin#onLoad() plugin's onLoad()} throws, the plugin is not
 * enabled and the load error is made available to operators.
 *
 * @author Irmo van den Berge (bergerkiller)
 * @version 1.8
 */
@SuppressWarnings("unchecked")
public class Preloader extends JavaPlugin {
    private final String mainClassName;
    private final List<Depend> dependList;
    private final List<String> preloaderCommands;
    private final List<Depend> missingDepends = new ArrayList<>();
    private String loadError = null;

    public Preloader() {
        // Parse the required information from plugin.yml
        // If anything goes wrong, abort right away!
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(readPluginYAML(this));
            ConfigurationSection preloaderConfig = config.getConfigurationSection("preloader");
            if (preloaderConfig == null) {
                throw new IllegalStateException("plugin.yml has no preloader configuration");
            }
            mainClassName = preloaderConfig.getString("main");
            if (mainClassName == null) {
                throw new IllegalStateException("plugin.yml preloader configuration declares no main class");
            }

            // Initialize all 'depend' entries put in plugin.yml
            // This is either a name: url mapping, or a List<Node>
            List<?> dependConfigList = preloaderConfig.getList("depend");
            if (dependConfigList != null) {
                dependList = new ArrayList<Depend>(dependConfigList.size());
                for (Object dependItem : dependConfigList) {
                    if (!(dependItem instanceof Map)) {
                        continue;
                    }
                    Map<String, Object> dependConfig = (Map<String, Object>) dependItem;
                    String name = (String) dependConfig.getOrDefault("name", null);
                    if (name == null) {
                        continue;
                    }
                    String url = (String) dependConfig.getOrDefault("url", "");
                    dependList.add(new Depend(name, url));
                }
            } else {
                ConfigurationSection dependConfig = preloaderConfig.getConfigurationSection("depend");
                if (dependConfig == null) {
                    dependList = Collections.emptyList();
                } else {
                    Set<String> names = dependConfig.getKeys(false);
                    dependList = new ArrayList<Depend>(names.size());
                    for (String name : names) {
                        dependList.add(new Depend(name, dependConfig.getString(name)));
                    }
                }
            }

            // Check that commands need to be registered if loading fails
            List<String> preloaderCommandsTmp = preloaderConfig.getStringList("commands");
            if (preloaderCommandsTmp == null || preloaderCommandsTmp.isEmpty()) {
                preloaderCommands = Collections.emptyList();
            } else {
                preloaderCommands = new ArrayList<String>(preloaderCommandsTmp);
            }
        } catch (InvalidConfigurationException ex) {
            throw new IllegalStateException("Corrupt jar: Failed to load plugin.yml", ex);
        }
    }

    @Override
    @SuppressWarnings({"deprecation"})
    public void onLoad() {
        // Verify all the hard depend plugins are available
        missingDepends.clear();
        for (Depend depend : dependList) {
            if (this.getServer().getPluginManager().getPlugin(depend.name) == null) {
                missingDepends.add(depend);
            }
        }
        if (!missingDepends.isEmpty()) {
            return;
        }

        // Now that we know all plugins we need are there we can safely make them hard-dependencies
        // They cannot be hard-dependencies before, otherwise this preloader cannot handle onEnabled()
        // It is also a problem if the main plugin class references classes from a hard dependency,
        // because then class not found exceptions will be thrown when instantiating the plugin.
        {
            PluginDescriptionFile description = this.getDescription();
            List<String> newHardDepend = new ArrayList<String>(description.getDepend());
            for (Depend depend : dependList) {
                if (!newHardDepend.contains(depend.name)) {
                    newHardDepend.add(depend.name);
                }
            }
            try {
                Field field = PluginDescriptionFile.class.getDeclaredField("depend");
                field.setAccessible(true);
                field.set(description, ImmutableList.copyOf(newHardDepend));
            } catch (Throwable t) {
                this.getLogger().log(Level.SEVERE, "Failed to update depend list", t);
            }
        }

        // Get up-front
        final String pluginName = this.getName();

        // Load the main class as declared in the preloader configuration
        final Class<?> mainClass;
        try {
            mainClass = this.getClassLoader().loadClass(mainClassName);
        } catch (ClassNotFoundException e) {
            this.getLogger().log(Level.SEVERE, "Failed to load the plugin main class", e);
            this.loadError = "Failed to load the plugin main class - check server log!";
            return;
        }

        // Inside the JavaPlugin constructor it initializes itself using the PluginClassLoader
        // As part of this, it checks that the plugin and pluginInit fields are unset.
        // So before we initialize a new instance, unset these fields.
        this.setLoaderPluginField(null, pluginName);

        // Initialize the plugin class the same way this preloader class was initialized
        // This is normally done inside the constructor of PluginClassLoader
        final JavaPlugin mainPlugin;
        try {
            mainPlugin = (JavaPlugin) mainClass.newInstance();
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "Failed to call plugin constructor", t);
            this.loadError = "Failed to call plugin constructor - check server log!";
            this.setLoaderPluginField(this, pluginName); // Undo this, otherwise state is corrupted
            return;
        }

        // Normally done inside PluginClassLoader constructor, so fix up everything now
        swapPluginFieldEverywhere(this, mainPlugin, pluginName);

        // Re-initialize the PluginClassLoader with the new main plugin
        // Call onLoad - if this fails, it's as if onLoad failed of the plugin itself
        try {
            mainPlugin.onLoad();
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "An error occurred during onLoad()", t);
            this.loadError = "Failed to load the plugin - check server log!";
            this.swapPluginFieldEverywhere(mainPlugin, this, pluginName); // Undo registration, otherwise state is corrupted
            return;
        }
    }

    @Override
    public void onEnable() {
        // Log about these missing dependencies
        if (!missingDepends.isEmpty()) {
            missingDepends.forEach(depend -> {
                final PluginDescriptionFile desc = getDescription();
                getLogger().log(Level.SEVERE, "Plugin " + desc.getName() + " " + desc.getVersion() +
                        " requires plugin " + depend.name + " to be installed! But it is not!");
                if (!depend.url.isEmpty()) {
                    getLogger().log(Level.SEVERE, "Download " + depend.name + " from " + depend.url);
                }
            });
        }

        // If nothing is wrong, something weird is going on
        // It should never get here...
        if (this.loadError == null && this.missingDepends.isEmpty()) {
            this.loadError = "Preloading failed - unsupported server?";
        }
        if (this.loadError != null) {
            getLogger().log(Level.SEVERE, "Not enabled because plugin could not be loaded! - Check server log");
        }

        // Register commands late if specified
        this.preloaderCommands.forEach(commandName -> {
            try {
                PluginCommand command;
                {
                    Constructor<PluginCommand> constr = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
                    constr.setAccessible(true);
                    command = constr.newInstance(commandName, this);
                    command.setDescription("Plugin " + getName() + " could not be loaded!");
                }

                command.setExecutor((sender, label, e_cmd, args) -> {
                    showErrors(sender);
                    return true;
                });

                Field commandMapField = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
                commandMapField.setAccessible(true);
                CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getPluginManager());
                commandMap.register(this.getName(), command);
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Failed to register preloader fallback command " + commandName, t);
            }
        });

        // Install a Listener into the server with the sole task of annoying people
        // Tell them the plugin is not enabled for the reasons as stated
        this.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                if (event.getPlayer().isOp()) {
                    showErrors(event.getPlayer());
                }
            }
        }, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        showErrors(sender);
        return true;
    }

    private void showErrors(CommandSender sender) {
        if (this.loadError != null) {
            sender.sendMessage(ChatColor.RED + "There was a fatal error initializing " + this.getName());
            sender.sendMessage(ChatColor.RED + this.loadError);
        } else {
            sender.sendMessage(ChatColor.RED + "Plugin " + this.getName() + " could not be enabled!");
            sender.sendMessage(ChatColor.RED + "Please install these additional dependencies:");
            for (Depend depend : this.missingDepends) {
                sender.sendMessage(ChatColor.RED + "  ======== " + depend.name + " ========");
                if (!depend.url.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "  > " + ChatColor.WHITE + ChatColor.UNDERLINE + depend.url);
                }
            }
        }
    }

    private void swapPluginFieldEverywhere(JavaPlugin old_plugin, JavaPlugin plugin, String pluginName) {
        // Plugin ClassLoader itself
        setLoaderPluginField(plugin, pluginName);

        PluginManager manager = this.getServer().getPluginManager();

        // Check whether paper or not
        PluginManager paperManager = null;
        try {
            Field paperPluginManagerField = manager.getClass().getDeclaredField("paperPluginManager");
            paperPluginManagerField.setAccessible(true);
            paperManager = (PluginManager) paperPluginManagerField.get(manager);
        } catch (Throwable t) { /* ignore */ }

        if (paperManager != null) {
            swapPluginFieldEverywherePaper(paperManager, old_plugin, plugin, pluginName);
            return;
        }

        swapPluginFieldEverywhereSpigot(manager, old_plugin, plugin, pluginName);
    }

    private void swapPluginFieldEverywhereSpigot(Object manager, JavaPlugin old_plugin, JavaPlugin plugin, String pluginName) {
        // Now replace the plugin instance in all other places
        // It's absolutely important we replace it EVERYWHERE or things will probably break!
        try {
            // private final List<Plugin> plugins
            {
                Field pluginsField = manager.getClass().getDeclaredField("plugins");
                pluginsField.setAccessible(true);
                List<Object> plugins = (List<Object>) pluginsField.get(manager);
                int index = plugins.indexOf(old_plugin);
                if (index == -1) {
                    throw new IllegalStateException("Preloader does not exist in plugins list");
                }
                plugins.set(index, plugin);
            }

            // private final Map<String, Plugin> lookupNames
            {
                Field lookupNamesField = manager.getClass().getDeclaredField("lookupNames");
                lookupNamesField.setAccessible(true);
                Map<Object, Object> lookupNames = (Map<Object, Object>) lookupNamesField.get(manager);
                boolean found = false;
                for (Map.Entry<Object, Object> e : lookupNames.entrySet()) {
                    if (e.getValue() == old_plugin) {
                        e.setValue(plugin);
                        found = true;
                    }
                }
                if (!found) {
                    throw new IllegalStateException("Preloader does not exist in lookupNames mapping");
                }
            }
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to fully register the plugin into the server", t);
        }
    }

    private void swapPluginFieldEverywherePaper(PluginManager manager, JavaPlugin old_plugin, JavaPlugin plugin, String pluginName) {
        Object instanceManager;
        try {
            // Get PaperPluginManagerImpl -> PaperPluginInstanceManager instanceManager
            Field instanceManagerField = manager.getClass().getDeclaredField("instanceManager");
            instanceManagerField.setAccessible(true);
            instanceManager = instanceManagerField.get(manager);
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to fully register the plugin into the server", t);
            return;
        }

        // This appears to work for the moment. Same field names and types as Spigot...
        swapPluginFieldEverywhereSpigot(instanceManager, old_plugin, plugin, pluginName);
    }

    private void setLoaderPluginField(JavaPlugin plugin, String pluginName) {
        ClassLoader loader = this.getClassLoader();
        try {
            Field pluginField = loader.getClass().getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(loader, plugin);
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to update 'plugin' field", t);
        }
        try {
            Field pluginInitField = loader.getClass().getDeclaredField("pluginInit");
            pluginInitField.setAccessible(true);
            pluginInitField.set(loader, plugin);
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to update 'pluginInit' field", t);
        }

        // Check whether the class loader is using Paper's plugin loader system
        boolean isPaperLoader = false;
        try {
            Class<?> paperLoaderType = Class.forName("io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader");
            isPaperLoader = paperLoaderType.isInstance(loader);
        } catch (Throwable t) { /* ignore */ }

        if (isPaperLoader) {
            setLoaderPluginFieldPaper(loader, plugin, pluginName);
            return;
        }

        // Default: Spigot/Bukkit
        setLoaderPluginFieldSpigot(loader, plugin, pluginName);
    }

    private void setLoaderPluginFieldPaper(ClassLoader loader, JavaPlugin plugin, String pluginName) {
        // No extra work appears to be needed on Paper server.

        /*
        try {
            Field classLoaderGroupField = loader.getClass().getDeclaredField("classLoaderGroup");
            classLoaderGroupField.setAccessible(true);
            Object classLoaderGroup = classLoaderGroupField.get(loader);
            System.out.println("LOADER: "  + classLoaderGroup.getClass());
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to update class loader registry", t);
        }
        */
    }

    private void setLoaderPluginFieldSpigot(ClassLoader loader, JavaPlugin plugin, String pluginName) {
        // Make sure that during initialization (null) the PluginClassLoader for this plugin
        // is not registered in the 'global' JavaPluginLoader loaders field. Having this here
        // can cause a nullpointer exception because the plugin is initializing. Normally
        // it is also not registered here while calling the constructor.
        try {
            Field globalLoaderField = loader.getClass().getDeclaredField("loader");
            globalLoaderField.setAccessible(true);
            JavaPluginLoader globalLoader = (JavaPluginLoader) globalLoaderField.get(loader);
            Field globalLoaderPluginLoadersField = JavaPluginLoader.class.getDeclaredField("loaders");
            globalLoaderPluginLoadersField.setAccessible(true);
            Object rawLoaders = globalLoaderPluginLoadersField.get(globalLoader);
            if (rawLoaders instanceof List) {
                // Since Bukkit 1.10 onwards
                List<Object> pluginLoaders = (List<Object>) rawLoaders;
                if (plugin == null) {
                    pluginLoaders.remove(loader);
                } else if (!pluginLoaders.contains(loader)) {
                    pluginLoaders.add(loader);
                }
            } else if (rawLoaders instanceof Map) {
                // Bukkit/Minecraft 1.8 to 1.9.4 used a Map by plugin name
                Map<String, Object> pluginLoaders = (Map<String, Object>) rawLoaders;
                if (plugin == null) {
                    if (pluginLoaders.get(pluginName) == loader) {
                        pluginLoaders.remove(pluginName);
                    }
                } else {
                    if (pluginLoaders.get(pluginName) == null) {
                        pluginLoaders.put(pluginName, loader);
                    }
                }
            } else {
                throw new IllegalStateException("Unknown loaders field type: " + rawLoaders.getClass());
            }
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to update class loader registry", t);
        }
    }

    /**
     * Locates the plugin.yml of a plugin. Some plugins (*cough* AsyncWorldEdit)
     * inject class loader stuff into the server that breaks getResource("plugin.yml").
     * This method tries to use findResource(), which doesn't suffer from this.
     *
     * @param plugin The plugin to read the plugin.yml of
     * @return Plugin YAML text content
     */
    private String readPluginYAML(Plugin plugin) {
        java.io.InputStream found_stream = null;
        if (plugin instanceof JavaPlugin) {
            try {
                java.lang.reflect.Method m = JavaPlugin.class.getDeclaredMethod("getClassLoader");
                m.setAccessible(true);
                ClassLoader loader = (ClassLoader) m.invoke(plugin);
                if (loader instanceof java.net.URLClassLoader) {
                    java.net.URL resource = ((java.net.URLClassLoader) loader).findResource("plugin.yml");
                    if (resource != null) {
                        java.net.URLConnection connection = resource.openConnection();
                        connection.setUseCaches(false);
                        found_stream = connection.getInputStream();
                    }
                }
            } catch (Throwable t) {
                this.getLogger().log(java.util.logging.Level.WARNING,
                        "Error selecting plugin.yml of " + plugin.getName() +
                        ", trying fallback", t);
            }
        }
        if (found_stream == null) {
            found_stream = plugin.getResource("plugin.yml");
        }
        if (found_stream == null) {
            throw new IllegalStateException("Failed to find plugin.yml");
        }
        try (java.io.InputStream stream = found_stream) {
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int length; (length = stream.read(buffer)) != -1; ) {
                result.write(buffer, 0, length);
            }
            return new String(result.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to read plugin.yml", ex);
        }
    }

    private static final class Depend {
        public final String name;
        public final String url;

        public Depend(String name, String url) {
            this.name = name.replace(' ', '_');
            this.url = url;
        }
    }
}
