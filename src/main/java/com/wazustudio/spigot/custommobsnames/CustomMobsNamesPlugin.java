package com.wazustudio.spigot.custommobsnames;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

/**
 * Pone un nombre personalizado (con colores) encima de las criaturas,
 * definido desde la sección {@code mobs} de config.yml.
 *
 * Compatible con Minecraft 26.1 / Paper (Java 25).
 *
 * Detalles de la implementación:
 *  - Los nombres se aplican al spawnear (EntitySpawnEvent) y al cargar chunks
 *    (ChunkLoadEvent). En los spawns también se re-aplica 1 tick después a través
 *    del EntityScheduler para asegurar que el nameplate llega a todos los clientes.
 *  - Los mannequins muestran "NPC" debajo del nombre por defecto (comportamiento
 *    vanilla desde 25w36b). El plugin lo quita automáticamente, o pone una
 *    descripción personalizada si se configura la clave {@code description}.
 *  - La etiqueta con el nombre solo se muestra cuando hay un jugador a menos de
 *    {@code settings.name-tag-range} bloques (por defecto 10). Con {@code -1} o
 *    {@code 0} las etiquetas se ven siempre, como en la versión original.
 */
public final class CustomMobsNamesPlugin extends JavaPlugin implements Listener {

    private static final String PREFIX = "§6[CustomMobsNames] §r";

    private final Map<String, MobConfig> mobConfigs = new ConcurrentHashMap<>();
    private final List<String> disabledWorlds = new ArrayList<>();

    /** Entidades con nombre puesto por este plugin (para controlar su etiqueta). */
    private final Set<UUID> namedEntities = ConcurrentHashMap.newKeySet();

    private boolean setMobNameOnSpawn = true;
    private boolean setMobNameOnChunkLoad = true;

    /** Distancia en bloques a la que se muestra la etiqueta. <= 0 = siempre visible. */
    private int nameTagRange = 10;
    /** Cada cuántos ticks se comprueba la distancia de los jugadores (20 = 1 segundo). */
    private int nameTagCheckInterval = 20;

    private ScheduledTask visibilityTask;

    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
        getServer().getPluginManager().registerEvents(this, this);
        new UpdateChecker(this).checkForUpdates();
    }

    @Override
    public void onDisable() {
        cancelVisibilityTask();
    }

    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command,
            @NonNull String label, @NonNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("custommobsnames.reload")) {
                sender.sendMessage(PREFIX + "§cNo tienes permiso para usar este comando.");
                return true;
            }
            try {
                reload();
                sender.sendMessage(PREFIX + "§aConfiguración recargada correctamente.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error al recargar la configuración", e);
                sender.sendMessage(PREFIX + "§cError al recargar, revisa la consola.");
            }
            return true;
        }
        sender.sendMessage(PREFIX + "§7Usa §e/custommobsnames reload§7 para recargar la configuración.");
        return true;
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!setMobNameOnSpawn) {
            return;
        }
        Entity entity = event.getEntity();
        applyName(entity);
        // En Paper, la metadata del spawn se envía justo tras el evento. Re-aplicamos
        // un tick después por si el paquete ya se había construido con el nombre vacío
        // o sin la descripción del mannequin (por eso la etiqueta "NPC" podía quedarse).
        if (entity.isValid() && entity.isTicking()) {
            entity.getScheduler().runDelayed(this, scheduledTask -> {
                if (entity.customName() == null || !entity.isCustomNameVisible()) {
                    applyName(entity);
                } else {
                    // El nombre llegó bien; nos aseguramos solo de la descripción
                    // de los mannequins, que es el texto que sale debajo del nombre.
                    applyMannequinDescription(entity);
                }
            }, () -> {
            }, 1);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!setMobNameOnChunkLoad) {
            return;
        }
        if (!isWorldEnabled(event.getWorld())) {
            return;
        }
        for (Entity entity : event.getChunk().getEntities()) {
            applyName(entity);
        }
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        // Evita acumular UUIDs de mobs que ya no existen (muertos o descargados).
        namedEntities.remove(event.getEntity().getUniqueId());
    }

    private void applyName(Entity entity) {
        if (entity == null || entity.isDead() || entity instanceof Player) {
            return;
        }
        if (!isWorldEnabled(entity.getWorld())) {
            return;
        }
        MobConfig config = findMobConfig(entity);
        if (config == null) {
            return;
        }
        // Los mobs que ya tienen nombre (mascotas, etc.) no se re-nombran salvo force-change: true
        if (!config.names.isEmpty()) {
            if (entity.customName() == null || config.forceChange) {
                Component name = config.names.get(ThreadLocalRandom.current().nextInt(config.names.size()));
                entity.customName(name);
                namedEntities.add(entity.getUniqueId());
                updateTagVisibility(entity);
            } else if (!entity.isCustomNameVisible()) {
                namedEntities.add(entity.getUniqueId());
                updateTagVisibility(entity);
            }
        }

        // La descripción se aplica SIEMPRE, independiente del nombre:
        // los mannequins muestran "NPC" bajo el nombre por defecto (vanilla 26.1),
        // y se quita automáticamente (o se usa la descripción configurada).
        applyMannequinDescription(entity);
    }

    /**
     * Muestra u oculta la etiqueta según si hay algún jugador dentro de
     * {@code name-tag-range} bloques. Con rango <= 0 siempre se muestra.
     */
    private void updateTagVisibility(Entity entity) {
        if (nameTagRange <= 0) {
            entity.setCustomNameVisible(true);
            return;
        }
        entity.setCustomNameVisible(isPlayerNearby(entity, (double) nameTagRange * nameTagRange));
    }

    private boolean isPlayerNearby(Entity entity, double rangeSquared) {
        Location location = entity.getLocation();
        for (Player player : entity.getWorld().getPlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            if (location.distanceSquared(player.getLocation()) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aplica (o quita) el texto que los mannequins muestran debajo del nombre.
     * Sin la clave {@code description} en la config, se llama a {@code setDescription(null)},
     * que en Paper activa el flag vanilla {@code hide_description} y hace desaparecer
     * por completo la etiqueta "NPC" que traen por defecto.
     */
    private void applyMannequinDescription(Entity entity) {
        if (entity instanceof Mannequin mannequin) {
            MobConfig config = findMobConfig(entity);
            if (config != null) {
                mannequin.setDescription(config.description);
            }
        }
    }

    private MobConfig findMobConfig(Entity entity) {
        // Clave clásica del plugin: nombre del enum en minúsculas. Ej: PIG -> pig, MUSHROOM_COW -> mushroom_cow
        String enumName = entity.getType().name().toLowerCase(Locale.ROOT);
        MobConfig config = mobConfigs.get(enumName);
        if (config != null) {
            return config;
        }
        // También se acepta el id real de Minecraft. Ej: mooshroom, snow_golem, zombified_piglin
        return mobConfigs.get(entity.getType().getKey().getKey());
    }

    private void reload() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        setMobNameOnSpawn = cfg.getBoolean("settings.set-mob-name-on-spawn", true);
        setMobNameOnChunkLoad = cfg.getBoolean("settings.set-mob-name-on-chunk-load", true);
        nameTagRange = cfg.getInt("settings.name-tag-range", 10);
        nameTagCheckInterval = Math.max(1, cfg.getInt("settings.name-tag-check-interval", 20));

        disabledWorlds.clear();
        disabledWorlds.addAll(cfg.getStringList("settings.disabled-worlds"));

        mobConfigs.clear();
        ConfigurationSection mobsSection = cfg.getConfigurationSection("mobs");
        if (mobsSection != null) {
            for (String mobKey : mobsSection.getKeys(false)) {
                ConfigurationSection mobSection = mobsSection.getConfigurationSection(mobKey);
                if (mobSection == null) {
                    continue;
                }
                boolean forceChange = mobSection.getBoolean("force-change", false);
                List<Component> names = new ArrayList<>();
                for (String raw : mobSection.getStringList("custom-names")) {
                    if (raw == null || raw.isEmpty()) {
                        continue;
                    }
                    names.add(parseName(raw));
                }
                Component description = null;
                String rawDescription = mobSection.getString("description");
                if (rawDescription != null && !rawDescription.isEmpty()) {
                    description = parseName(rawDescription);
                }
                mobConfigs.put(mobKey.toLowerCase(Locale.ROOT), new MobConfig(forceChange, names, description));
            }
        }

        getLogger().info("Configuración cargada: " + mobConfigs.size() + " criatura(s), "
                + (setMobNameOnSpawn ? "" : "sin ") + "cambiar en spawn, "
                + (setMobNameOnChunkLoad ? "" : "sin ") + "cambiar en carga de chunks, "
                + (nameTagRange > 0
                        ? "etiqueta visible solo a " + nameTagRange + " bloques."
                        : "etiqueta siempre visible."));

        // Aplica a los mobs ya cargados del servidor
        if (setMobNameOnChunkLoad) {
            for (World world : getServer().getWorlds()) {
                if (isWorldEnabled(world)) {
                    for (Entity entity : world.getEntities()) {
                        applyName(entity);
                    }
                }
            }
        }

        // (Re)lanza la tarea periódica que oculta/muestra las etiquetas según la distancia.
        startVisibilityTask();
    }

    private void startVisibilityTask() {
        cancelVisibilityTask();
        if (nameTagRange <= 0) {
            return;
        }
        visibilityTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> runVisibilityCheck(), 1, nameTagCheckInterval);
    }

    private void cancelVisibilityTask() {
        if (visibilityTask != null) {
            visibilityTask.cancel();
            visibilityTask = null;
        }
    }

    /**
     * Oculta o muestra las etiquetas de las criaturas gestionadas según
     * si hay jugadores dentro del rango configurado.
     */
    private void runVisibilityCheck() {
        if (namedEntities.isEmpty()) {
            return;
        }
        double rangeSquared = (double) nameTagRange * nameTagRange;
        for (World world : getServer().getWorlds()) {
            if (!isWorldEnabled(world)) {
                continue;
            }
            for (Entity entity : world.getEntities()) {
                if (!namedEntities.contains(entity.getUniqueId())) {
                    continue;
                }
                if (entity.isDead() || !entity.isValid()) {
                    namedEntities.remove(entity.getUniqueId());
                    continue;
                }
                boolean visible = isPlayerNearby(entity, rangeSquared);
                if (entity.isCustomNameVisible() != visible) {
                    entity.setCustomNameVisible(visible);
                }
            }
        }
    }

    /**
     * Convierte un nombre de la config en un Component de Adventure.
     * Soporta los códigos clásicos {@code &c} (legacy) y también MiniMessage
     * (por ejemplo {@code <red>}, {@code <gradient:red:blue>}).
     */
    private Component parseName(String raw) {
        if (raw.indexOf('<') != -1 && raw.indexOf('>') != -1) {
            try {
                return miniMessage.deserialize(raw);
            } catch (RuntimeException ignored) {
                // no era MiniMessage válido, se trata como legacy
            }
        }
        return legacySerializer.deserialize(raw);
    }

    private boolean isWorldEnabled(World world) {
        if (world == null) {
            return false;
        }
        for (String worldName : disabledWorlds) {
            if (world.getName().equalsIgnoreCase(worldName)) {
                return false;
            }
        }
        return true;
    }

    private static final class MobConfig {
        private final boolean forceChange;
        private final List<Component> names;
        private final Component description;

        private MobConfig(boolean forceChange, List<Component> names, Component description) {
            this.forceChange = forceChange;
            this.names = names;
            this.description = description;
        }
    }
}
