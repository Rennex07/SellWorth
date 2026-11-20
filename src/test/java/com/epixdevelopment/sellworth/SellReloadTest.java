package com.epixdevelopment.sellworth;

import com.epixdevelopment.sellworth.config.ConfigManager;
import com.epixdevelopment.sellworth.storage.SellDataStore;
import com.epixdevelopment.sellworth.storage.SqlSellDataStore;
import com.epixdevelopment.sellworth.storage.YamlSellDataStore;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MaterialData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.util.UnsafeValuesMock;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SellReloadTest {
    private static final String CATEGORY = "test";
    private static final String ITEM = "COBBLESTONE";
    private static final String PRICE_KEY = "cobblestone-value";

    @AfterEach
    void stopServer() throws Exception {
        try {
            MockBukkit.unmock();
        } finally {
            field("instance").set(null, null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"yaml", "sqlite"})
    void priceCommandsPreserveProgressAndApplyNewPrices(String storageType) throws Exception {
        assertReloadPreservesProgress(storageType, 0, null);
    }

    @Test
    @EnabledIfSystemProperty(named = "sellworth.test.mysql.port", matches = "[0-9]+")
    void mysqlPriceCommandsPreserveProgressAndApplyNewPrices() throws Exception {
        int port = Integer.getInteger("sellworth.test.mysql.port");
        String database = "sellworth_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(mysqlUrl(port, ""), "root", "");
                var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + database);
            try {
                assertReloadPreservesProgress("mysql", port, database);
            } finally {
                try {
                    MockBukkit.unmock();
                } finally {
                    statement.executeUpdate("DROP DATABASE " + database);
                }
            }
        }
    }

    private void assertReloadPreservesProgress(String storageType, int port, String database) throws Exception {
        ServerMock server = MockBukkit.mock(new ServerMock() {
            private final UnsafeValuesMock unsafe = new UnsafeValuesMock() {
                @Override
                public Material fromLegacy(MaterialData material, boolean itemPriority) {
                    // The price menu scans every Material, including legacy entries with no mock item type.
                    return material.getItemType().isLegacy() ? Material.AIR : super.fromLegacy(material, itemPriority);
                }
            };

            @Override
            public UnsafeValuesMock getUnsafe() {
                return unsafe;
            }
        });
        Sell plugin = (Sell) server.getPluginManager().loadPlugin(Sell.class);
        new ConfigManager(plugin).setup();
        var config = plugin.getConfig();
        config.set("storage.type", storageType);
        if (database != null) {
            config.set("storage.mysql.host", "127.0.0.1");
            config.set("storage.mysql.port", port);
            config.set("storage.mysql.database", database);
        }
        config.set("categories", null);
        config.set("categories." + CATEGORY, List.of(Map.of(PRICE_KEY, 10.0, "dirt-value", 3.0)));
        config.set("progress-menu.levels", null);
        config.set("progress-menu.levels.test.amountNeeded", 50.0);
        config.set("progress-menu.levels.test.multi", 1.5);
        plugin.saveConfig();

        PlayerMock player = server.addPlayer();
        UUID uuid = player.getUniqueId();
        UUID previouslyDisabled = UUID.randomUUID();
        SellDataStore disk = switch (storageType) {
            case "sqlite" -> new SqlSellDataStore(plugin,
                    "jdbc:sqlite:" + new File(plugin.getDataFolder(), "sellworth.db"), null, null);
            case "mysql" -> new SqlSellDataStore(plugin, mysqlUrl(port, database), "root", "");
            default -> new YamlSellDataStore(plugin);
        };
        disk.init();
        disk.saveAll(Map.of(uuid, 20.0), Map.of(uuid, Map.of(CATEGORY, 20.0)),
                Map.of(uuid, Map.of(ITEM, new Sell.Stats(2, 20.0))), Set.of(previouslyDisabled));
        var yamlFile = new File(plugin.getDataFolder(), "databases.yml").toPath();
        if (storageType.equals("yaml")) {
            String legacyYaml = Files.readString(yamlFile).replace("- " + previouslyDisabled,
                    "- !!java.util.UUID '" + previouslyDisabled + "'");
            assertTrue(legacyYaml.contains("!!java.util.UUID"));
            Files.writeString(yamlFile, legacyYaml);
        }

        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.isEnabled());
        assertEquals(20.0, plugin.getRawTotalSold(uuid, CATEGORY));
        assertFalse(plugin.isWorthEnabled(previouslyDisabled));
        plugin.recordSale(player, Map.of(ITEM, new Sell.Stats(4, 40.0)));
        assertProgress(plugin, uuid);

        player.setOp(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.COBBLESTONE));

        // Keep the preference write queued across all three commands.
        ExecutorService storageExecutor = (ExecutorService) field("storageExecutor").get(plugin);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        storageExecutor.execute(() -> {
            try {
                releaseWriter.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            plugin.setWorthEnabled(uuid, false);
            assertTrue(server.dispatchCommand(player, "sell addhanditem test 25"));
            assertEquals(25.0, plugin.getPrice(PRICE_KEY));
            assertProgress(plugin, uuid);
            assertFalse(plugin.isWorthEnabled(uuid));

            assertTrue(server.dispatchCommand(player, "sell removeitem test cobblestone"));
            assertFalse(plugin.getItemValues().containsKey(PRICE_KEY));
            assertEquals(3.0, plugin.getPrice("dirt-value"));
            assertProgress(plugin, uuid);

            File configFile = new File(plugin.getDataFolder(), "config.yml");
            var edited = YamlConfiguration.loadConfiguration(configFile);
            edited.set("categories." + CATEGORY, List.of(Map.of(PRICE_KEY, 35.0)));
            edited.set("storage.type", storageType.equals("yaml") ? "sqlite" : "yaml");
            edited.save(configFile);
            assertTrue(server.dispatchCommand(player, "sell reload"));
            assertEquals(35.0, plugin.getPrice(PRICE_KEY));
            assertProgress(plugin, uuid);
            assertFalse(plugin.isWorthEnabled(uuid));
        } finally {
            releaseWriter.countDown();
        }
        storageExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);

        Set<UUID> disabled = new HashSet<>();
        disk.init();
        disk.loadAll(new HashMap<>(), new HashMap<>(), new HashMap<>(), disabled);
        assertEquals(Set.of(uuid, previouslyDisabled), disabled);

        server.getPluginManager().disablePlugin(plugin);
        var totals = new HashMap<UUID, Double>();
        var categories = new HashMap<UUID, Map<String, Double>>();
        var history = new HashMap<UUID, Map<String, Sell.Stats>>();
        disk.init();
        disk.loadAll(totals, categories, history, disabled);
        assertEquals(60.0, totals.get(uuid));
        assertEquals(60.0, categories.get(uuid).get(CATEGORY));
        assertEquals(6.0, history.get(uuid).get(ITEM).count);
        assertEquals(60.0, history.get(uuid).get(ITEM).revenue);
        assertEquals(Set.of(uuid, previouslyDisabled), disabled);
        if (storageType.equals("yaml")) {
            assertFalse(Files.readString(yamlFile).contains("!!java.util.UUID"));
        }
    }

    private static void assertProgress(Sell plugin, UUID uuid) {
        assertEquals(60.0, plugin.getRawTotalSold(uuid, null));
        assertEquals(60.0, plugin.getRawTotalSold(uuid, CATEGORY));
        assertEquals(1.5, plugin.getSellMultiplier(uuid, CATEGORY));
        Sell.Stats history = plugin.getHistory(uuid).get(ITEM);
        assertEquals(6.0, history.count);
        assertEquals(60.0, history.revenue);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = Sell.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String mysqlUrl(int port, String database) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + database + "?useSSL=false&characterEncoding=utf8";
    }
}
