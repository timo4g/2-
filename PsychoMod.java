package ru.example.psychomod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;

import java.util.*;

public class PsychoMod implements ModInitializer {

    public enum TaskType { KILL_MOBS, BREAK_BLOCKS, TAKE_NO_DAMAGE }

    public record Diagnosis(String name, String whisper,
                            List<StatusEffectInstance> effects,
                            List<String> hallucinations,
                            List<String> sounds,
                            TaskType taskType, Integer taskTarget, Integer taskSeconds,
                            List<StatusEffectInstance> failPenalties) {
        public boolean hasTask() { return taskType != null; }
    }

    private static class ActiveTask {
        TaskType type;
        int target;
        int progress;
        long deadlineTick;
        List<StatusEffectInstance> penalties;
        boolean finished;
    }

    private static class Builder {
        final String name, whisper;
        final List<StatusEffectInstance> e = new ArrayList<>();
        final List<String> h = new ArrayList<>(), s = new ArrayList<>();
        TaskType tt = null;
        Integer tg = null, ts = null;
        final List<StatusEffectInstance> pen = new ArrayList<>();

        Builder(String n, String w) { name = n; whisper = w; }
        Builder fx(StatusEffect t, int amp) { e.add(new StatusEffectInstance(t, -1, amp)); return this; }
        Builder fxD(StatusEffect t, int dur, int amp) { e.add(new StatusEffectInstance(t, dur, amp)); return this; }
        Builder hall(String... hs) { h.addAll(Arrays.asList(hs)); return this; }
        Builder snd(String... ss) { s.addAll(Arrays.asList(ss)); return this; }
        Builder task(TaskType type, int target, int sec, StatusEffectInstance... penalties) {
            tt = type; tg = target; ts = sec; pen.addAll(Arrays.asList(penalties)); return this;
        }
        Diagnosis build() { return new Diagnosis(name, whisper, e, h, s, tt, tg, ts, pen); }
    }

    private static StatusEffectInstance pen(StatusEffect t, int amp) {
        return new StatusEffectInstance(t, 2400, amp);
    }

    public static final Set<StatusEffect> MOD_EFFECTS = new HashSet<>();

    private PsychoConfig config;
    private final Random random = new Random();
    private final Map<UUID, ActiveTask> activeTasks = new HashMap<>();
    private final Map<UUID, Long> nextTaskAt = new HashMap<>();
    // Диагнозы, уже выданные игроку: имя -> тик окончания действия.
    private final Map<UUID, Set<String>> activeDiagnoses = new HashMap<>();
    private static PsychoMod INSTANCE;

    private final List<Diagnosis> diagnoses = buildDiagnoses();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        config = PsychoConfig.load();

        // Предмет-таблетка
        Item pill = new PillItem(new Item.Settings().maxCount(16));
        Registry.register(Registries.ITEM, new Identifier("psychomod", "pill"), pill);

        // Собираем все эффекты, которые считаются "расстройствами" (для таблетки и молока)
        for (Diagnosis d : diagnoses) {
            for (StatusEffectInstance f : d.effects()) MOD_EFFECTS.add(f.getEffectType());
            for (StatusEffectInstance f : d.failPenalties()) MOD_EFFECTS.add(f.getEffectType());
        }

        // Молоко больше не лечит
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!world.isClient && stack.getItem() == net.minecraft.item.Items.MILK_BUCKET) {
                if (player instanceof ServerPlayerEntity sp && hasAnyModEffect(sp)) {
                    sp.sendMessage(Text.literal("🥛 Молоко больше не помогает. Нужны таблетки.")
                            .formatted(Formatting.DARK_RED), false);
                    return TypedActionResult.fail(stack);
                }
            }
            return TypedActionResult.pass(stack);
        });

        // Трекинг заданий
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity p) {
                progressTask(p, TaskType.KILL_MOBS);
            }
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity sp) progressTask(sp, TaskType.BREAK_BLOCKS);
        });

        // После смерти эффекты исчезают, поэтому сбрасываем и учёт диагнозов
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                activeDiagnoses.remove(newPlayer.getUuid());
                activeTasks.remove(newPlayer.getUuid());
                PsychoNetwork.sendDiagnosis(newPlayer, "", 0);
                PsychoNetwork.sendTask(newPlayer, "", 0, 0, 0);
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity p) {
                ActiveTask t = activeTasks.get(p.getUuid());
                if (t != null && !t.finished && t.type == TaskType.TAKE_NO_DAMAGE) {
                    failTask(p, t);
                }
            }
            return true;
        });

        // Приветствие при входе в мир
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!config.welcomeEnabled) return;
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(Text.literal("🧠 Привет, " + player.getName().getString() + "!")
                    .formatted(Formatting.DARK_PURPLE, Formatting.BOLD), false);
            player.sendMessage(Text.literal(config.welcomeMessage).formatted(Formatting.GRAY), false);
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private boolean hasAnyModEffect(ServerPlayerEntity player) {
        for (StatusEffectInstance inst : player.getActiveStatusEffects().values()) {
            if (MOD_EFFECTS.contains(inst.getEffectType())) return true;
        }
        return false;
    }

    /** Таблетка снимает одно целое активное расстройство, а не один случайный эффект. */
    public static String tryUsePill(ServerPlayerEntity player) {
        if (INSTANCE == null) return null;
        Set<String> active = INSTANCE.activeDiagnoses.get(player.getUuid());
        if (active == null || active.isEmpty()) {
            // Учёт потерян (перезапуск сервера), но эффекты расстройств ещё висят на игроке
            List<StatusEffect> leftovers = new ArrayList<>();
            for (StatusEffectInstance inst : player.getActiveStatusEffects().values()) {
                if (MOD_EFFECTS.contains(inst.getEffectType())) leftovers.add(inst.getEffectType());
            }
            if (leftovers.isEmpty()) return null;
            StatusEffect picked = leftovers.get(new Random().nextInt(leftovers.size()));
            player.removeStatusEffect(picked);
            return "один из симптомов";
        }
        List<String> names = new ArrayList<>(active);
        String curedName = names.get(new Random().nextInt(names.size()));
        Diagnosis cured = INSTANCE.diagnoses.stream()
                .filter(d -> d.name().equals(curedName)).findFirst().orElse(null);
        active.remove(curedName);
        if (active.isEmpty()) INSTANCE.activeDiagnoses.remove(player.getUuid());

        // Удаляем эффекты вылеченного диагноза, но сохраняем те, которые нужны
        // другим одновременно действующим диагнозам.
        if (cured != null) {
            for (StatusEffectInstance effect : cured.effects()) {
                boolean stillNeeded = false;
                if (active != null) {
                    for (String otherName : active) {
                        Diagnosis other = INSTANCE.diagnoses.stream()
                                .filter(d -> d.name().equals(otherName)).findFirst().orElse(null);
                        if (other != null && other.effects().stream()
                                .anyMatch(e -> e.getEffectType() == effect.getEffectType())) {
                            stillNeeded = true;
                            break;
                        }
                    }
                }
                if (!stillNeeded) player.removeStatusEffect(effect.getEffectType());
            }
        }
        return cured == null ? curedName : cured.name();
    }

    private void onServerTick(MinecraftServer server) {
        long nowTick = server.getTicks();
        int intervalTicks = 20 * 60 * config.intervalMinutes;
        int baseDuration = 20 * 60 * config.effectDurationMinutes;

        // Выдача диагноза раз в intervalMinutes
        if (nowTick % Math.max(1, intervalTicks) == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if ((config.skipCreative && player.isCreative()) ||
                        (config.skipSpectator && player.isSpectator())) continue;

                // Диагнозы не истекают сами: повторно выдаём только после лечения таблеткой.
                Set<String> playerDiagnoses = activeDiagnoses.computeIfAbsent(
                        player.getUuid(), id -> new HashSet<>());
                List<Diagnosis> available = new ArrayList<>();
                for (Diagnosis diagnosis : diagnoses) {
                    if (!playerDiagnoses.contains(diagnosis.name())) available.add(diagnosis);
                }
                if (available.isEmpty()) continue;
                Diagnosis d = available.get(random.nextInt(available.size()));
                applyDiagnosis(player, d, baseDuration);
                playerDiagnoses.add(d.name());
            }
        }

        // Тик заданий
        if (config.tasksEnabled) {
            tickTasks(server, nowTick);
        }
    }

    private void applyDiagnosis(ServerPlayerEntity player, Diagnosis d, int baseDuration) {
        for (StatusEffectInstance f : d.effects()) {
            // Расстройство сохраняется, пока игрок не примет таблетку.
            StatusEffectInstance existing = player.getStatusEffect(f.getEffectType());
            if (existing == null || existing.getAmplifier() < f.getAmplifier()) {
                player.addStatusEffect(new StatusEffectInstance(f.getEffectType(), -1, f.getAmplifier()));
            }
        }

        player.sendMessage(Text.literal("⚠ Диагноз: «" + d.name() + "»")
                .formatted(Formatting.DARK_PURPLE, Formatting.BOLD), false);
        player.sendMessage(Text.literal("Эффекты: " + describeEffects(d))
                .formatted(Formatting.YELLOW), false);

        if (d.whisper() != null && !d.whisper().isEmpty() && random.nextDouble() < config.whisperChance) {
            player.sendMessage(Text.literal(d.whisper()).formatted(Formatting.GRAY, Formatting.ITALIC), false);
        }

        // HUD
        int hudTicks = d.hasTask() ? d.taskSeconds() * 20 : baseDuration;
        if (d.hasTask()) hudTicks = Math.max(hudTicks, baseDuration);
        PsychoNetwork.sendDiagnosis(player, d.name(), hudTicks);

        // Галлюцинации
        if (config.hallucinationsEnabled && !d.hallucinations().isEmpty()) {
            for (String type : d.hallucinations()) {
                int count = 1 + random.nextInt(3);
                for (int i = 0; i < count; i++) {
                    double x = player.getX() + (random.nextDouble() - 0.5) * 12;
                    double y = player.getY() + random.nextInt(3);
                    double z = player.getZ() + (random.nextDouble() - 0.5) * 12;
                    PsychoNetwork.sendHallucination(player, type, x, y, z);
                }
            }
        }

        // Голоса / звуки
        for (String snd : d.sounds()) {
            PsychoNetwork.sendSound(player, snd, 0.8F + random.nextFloat() * 0.4F, 0.9F + random.nextFloat() * 0.2F);
        }

        // Задание
        if (config.tasksEnabled && d.hasTask()) {
            ActiveTask t = new ActiveTask();
            t.type = d.taskType();
            t.target = d.taskTarget();
            t.progress = 0;
            t.deadlineTick = player.getServer().getTicks() + d.taskSeconds() * 20L;
            t.penalties = d.failPenalties();
            t.finished = false;
            activeTasks.put(player.getUuid(), t);
            sendTaskUpdate(player, t);
        }
    }

    private void tickTasks(MinecraftServer server, long nowTick) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ActiveTask t = activeTasks.get(player.getUuid());

            if (t != null && !t.finished) {
                if (nowTick >= t.deadlineTick) {
                    if (t.progress >= t.target) {
                        completeTask(player, t);
                    } else {
                        failTask(player, t);
                    }
                } else if (nowTick % 10 == 0) {
                    sendTaskUpdate(player, t);
                }
                continue;
            }

            // Нет активного задания — планируем следующее в случайное время
            long next = nextTaskAt.getOrDefault(player.getUuid(), 0L);
            if (next == 0L || nowTick >= next) {
                nextTaskAt.put(player.getUuid(), nowTick + randomTaskDelayTicks());
            }
        }
    }

    private void progressTask(ServerPlayerEntity player, TaskType type) {
        ActiveTask t = activeTasks.get(player.getUuid());
        if (t == null || t.finished || t.type != type) return;
        t.progress++;
        if (t.progress >= t.target) completeTask(player, t);
        else sendTaskUpdate(player, t);
    }

    private void completeTask(ServerPlayerEntity player, ActiveTask t) {
        t.finished = true;
        activeTasks.remove(player.getUuid());
        player.sendMessage(Text.literal("✔ Задание выполнено. Штраф отменён (наград нет).")
                .formatted(Formatting.GREEN), false);
        PsychoNetwork.sendTask(player, "", 0, 0, 0);
        scheduleNext(player.getServer(), player);
    }

    private void failTask(ServerPlayerEntity player, ActiveTask t) {
        t.finished = true;
        activeTasks.remove(player.getUuid());
        player.sendMessage(Text.literal("✖ Задание провалено! Штрафные эффекты.")
                .formatted(Formatting.DARK_RED, Formatting.BOLD), false);
        for (StatusEffectInstance p : t.penalties) {
            player.removeStatusEffect(p.getEffectType());
            player.addStatusEffect(new StatusEffectInstance(p.getEffectType(), p.getDuration(), p.getAmplifier()));
        }
        PsychoNetwork.sendTask(player, "", 0, 0, 0);
        scheduleNext(player.getServer(), player);
    }

    private long randomTaskDelayTicks() {
        int minMinutes = Math.max(1, config.taskMinMinutes);
        int maxMinutes = Math.max(minMinutes, config.taskMaxMinutes);
        int range = maxMinutes - minMinutes + 1;
        int delayMinutes = minMinutes + random.nextInt(range);
        return 20L * 60L * delayMinutes;
    }

    private void scheduleNext(MinecraftServer server, ServerPlayerEntity player) {
        nextTaskAt.put(player.getUuid(), server.getTicks() + randomTaskDelayTicks());
    }

    private void sendTaskUpdate(ServerPlayerEntity player, ActiveTask t) {
        int left = (int) Math.max(0, (t.deadlineTick - player.getServer().getTicks()) / 20);
        PsychoNetwork.sendTask(player, t.type.name(), t.target, t.progress, left);
    }

    private String describeEffects(Diagnosis d) {
        List<String> parts = new ArrayList<>();
        for (StatusEffectInstance e : d.effects()) {
            String id = Registries.STATUS_EFFECT.getId(e.getEffectType()).getPath();
            String name = switch (id) {
                case "speed" -> "Ускорение";
                case "slowness" -> "Замедление";
                case "haste" -> "Спешка (быстрая добыча)";
                case "mining_fatigue" -> "Утомление (медленная добыча)";
                case "strength" -> "Сила";
                case "instant_health" -> "Мгновенное лечение";
                case "instant_damage" -> "Мгновенный урон";
                case "jump_boost" -> "Прыгучесть";
                case "nausea" -> "Тошнота и искажение зрения";
                case "regeneration" -> "Регенерация";
                case "resistance" -> "Сопротивление урону";
                case "fire_resistance" -> "Огнестойкость";
                case "water_breathing" -> "Подводное дыхание";
                case "invisibility" -> "Невидимость";
                case "blindness" -> "Слепота";
                case "night_vision" -> "Ночное зрение";
                case "hunger" -> "Голод";
                case "weakness" -> "Слабость";
                case "poison" -> "Отравление";
                case "wither" -> "Иссушение";
                case "health_boost" -> "Дополнительное здоровье";
                case "absorption" -> "Поглощение урона";
                case "saturation" -> "Насыщение";
                case "glowing" -> "Свечение";
                case "levitation" -> "Левитация";
                case "luck" -> "Удача";
                case "unluck" -> "Неудача";
                case "slow_falling" -> "Медленное падение";
                case "conduit_power" -> "Сила проводника";
                case "dolphins_grace" -> "Грация дельфина";
                case "darkness" -> "Тьма и ограниченная видимость";
                default -> id;
            };
            parts.add(name + " (уровень " + (e.getAmplifier() + 1) + ")");
        }
        if (!d.hallucinations().isEmpty()) parts.add("галлюцинации: " + translateHallucinations(d.hallucinations()));
        if (!d.sounds().isEmpty()) parts.add("звуковые симптомы");
        if (d.hasTask()) {
            String task = switch (d.taskType()) {
                case KILL_MOBS -> "убить мобов: " + d.taskTarget();
                case BREAK_BLOCKS -> "сломать блоки: " + d.taskTarget();
                case TAKE_NO_DAMAGE -> "не получать урон";
            };
            parts.add("задание: " + task + " за " + d.taskSeconds() + " сек.");
        }
        return parts.isEmpty() ? "особые игровые симптомы" : String.join("; ", parts);
    }

    private String translateHallucinations(List<String> types) {
        List<String> names = new ArrayList<>();
        for (String type : types) names.add(switch (type) {
            case "zombie" -> "зомби";
            case "skeleton" -> "скелеты";
            case "spider" -> "пауки";
            case "creeper" -> "криперы";
            case "enderman" -> "эндермены";
            case "phantom" -> "фантомы";
            case "vex" -> "вредины";
            case "villager" -> "жители";
            case "blaze" -> "ифриты";
            default -> type;
        });
        return String.join(", ", new LinkedHashSet<>(names));
    }

    private List<Diagnosis> buildDiagnoses() {
        return List.of(
            // ---------- Только эффекты (35) ----------
            new Builder("Паранойя", "Кажется, за тобой следят...")
                .fx(StatusEffects.DARKNESS, 0).fx(StatusEffects.SLOWNESS, 1).build(),
            new Builder("Тревожное расстройство", "Сердце колотится без причины.")
                .fxD(StatusEffects.NAUSEA, 600, 0).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Депрессивный эпизод", "Всё бессмысленно. Даже шахта.")
                .fx(StatusEffects.SLOWNESS, 1).fx(StatusEffects.WEAKNESS, 1).fx(StatusEffects.MINING_FATIGUE, 0).build(),
            new Builder("Мания величия", "Ты — бог этого мира!")
                .fx(StatusEffects.STRENGTH, 1).fx(StatusEffects.SPEED, 1).fx(StatusEffects.JUMP_BOOST, 1).build(),
            new Builder("Клаустрофобия", "Стены сжимаются. Нужно на воздух!")
                .fxD(StatusEffects.BLINDNESS, 600, 0).fx(StatusEffects.SPEED, 0).build(),
            new Builder("Гиперактивность", "Слишком много энергии!")
                .fx(StatusEffects.SPEED, 2).fx(StatusEffects.HASTE, 1).fx(StatusEffects.HUNGER, 0).build(),
            new Builder("Амнезия", "Кто ты? Где ты? Как ты здесь оказался?")
                .fxD(StatusEffects.BLINDNESS, 200, 0).fxD(StatusEffects.NAUSEA, 600, 0).build(),
            new Builder("Мигрень", "Голова раскалывается, свет режет глаза.")
                .fxD(StatusEffects.NAUSEA, 900, 1).fx(StatusEffects.MINING_FATIGUE, 1).build(),
            new Builder("Лунатизм", "Ты ходишь во сне.")
                .fx(StatusEffects.INVISIBILITY, 0).fx(StatusEffects.SLOWNESS, 0).fx(StatusEffects.DARKNESS, 0).build(),
            new Builder("Нарколепсия", "Веки тяжелеют. Не усни...")
                .fxD(StatusEffects.BLINDNESS, 400, 0).fx(StatusEffects.SLOWNESS, 2).build(),
            new Builder("Синестезия", "Звуки расцвели красками.")
                .fx(StatusEffects.NIGHT_VISION, 0).fxD(StatusEffects.NAUSEA, 200, 0).build(),
            new Builder("Кататония", "Тело не слушается. Двигайся как робот.")
                .fx(StatusEffects.SLOWNESS, 4).fx(StatusEffects.WEAKNESS, 1).build(),
            new Builder("Биполярное расстройство", "Взлёт и падение без остановки.")
                .fx(StatusEffects.SPEED, 1).fx(StatusEffects.WEAKNESS, 1).fxD(StatusEffects.NAUSEA, 200, 0).build(),
            new Builder("Циклотимия", "Качели настроения.")
                .fx(StatusEffects.JUMP_BOOST, 0).fx(StatusEffects.SLOWNESS, 0).fx(StatusEffects.HASTE, 0).fx(StatusEffects.MINING_FATIGUE, 0).build(),
            new Builder("Синдром самозванца", "Ты не заслуживаешь этого алмаза.")
                .fx(StatusEffects.WEAKNESS, 0).fxD(StatusEffects.BLINDNESS, 300, 0).fx(StatusEffects.SLOWNESS, 0).build(),
            new Builder("Глоссофобия", "Слова застревают в горле.")
                .fx(StatusEffects.SLOWNESS, 1).fx(StatusEffects.WEAKNESS, 0).fxD(StatusEffects.NAUSEA, 300, 0).build(),
            new Builder("Гаптофобия", "Не прикасайся. Ничего. Ни к чему.")
                .fx(StatusEffects.SLOWNESS, 0).fxD(StatusEffects.NAUSEA, 400, 0).build(),
            new Builder("Мизофобия", "Всё вокруг грязное. Всё.")
                .fx(StatusEffects.HUNGER, 1).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Социофобия", "Все смотрят. Все оценивают.")
                .fx(StatusEffects.SLOWNESS, 1).fxD(StatusEffects.BLINDNESS, 300, 0).build(),
            new Builder("Дисморфофобия", "Это тело — не твоё.")
                .fxD(StatusEffects.NAUSEA, 400, 0).fx(StatusEffects.WEAKNESS, 0).fx(StatusEffects.SLOWNESS, 0).build(),
            new Builder("Антропофобия", "Держись подальше от людей.")
                .fx(StatusEffects.SLOWNESS, 1).fxD(StatusEffects.DARKNESS, 200, 0).build(),
            new Builder("Никтофобия", "Темнота смотрит на тебя.")
                .fxD(StatusEffects.DARKNESS, 1200, 0).fx(StatusEffects.SLOWNESS, 0).build(),
            new Builder("Акрофобия", "Не смотри вниз. Никогда.")
                .fxD(StatusEffects.NAUSEA, 300, 0).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Талассофобия", "Глубина зовёт. Не слушай.")
                .fx(StatusEffects.SLOWNESS, 1).fxD(StatusEffects.NAUSEA, 300, 0).build(),
            new Builder("Арахнофобия", "Они ползут. Откуда угодно.")
                .fxD(StatusEffects.DARKNESS, 400, 0).fx(StatusEffects.SLOWNESS, 0).fxD(StatusEffects.NAUSEA, 200, 0).build(),
            new Builder("Трипанофобия", "Иглы. Маленькие. Много.")
                .fx(StatusEffects.HUNGER, 1).fx(StatusEffects.WEAKNESS, 0).fxD(StatusEffects.NAUSEA, 300, 0).build(),
            new Builder("Кардиофобия", "Сердце бьётся неправильно. Ты это чувствуешь.")
                .fx(StatusEffects.SLOWNESS, 1).fxD(StatusEffects.NAUSEA, 200, 0).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Ипохондрия", "Болит. Значит умираешь. Наверное.")
                .fxD(StatusEffects.NAUSEA, 400, 0).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Паническая атака", "ДЫШИ. ДЫШИ. НЕ МОЖЕШЬ ДЫШАТЬ.")
                .fx(StatusEffects.SPEED, 2).fxD(StatusEffects.NAUSEA, 600, 0).fxD(StatusEffects.DARKNESS, 400, 0).build(),
            new Builder("Гипомания", "Слишком светло. Слишком быстро. Слишком всё.")
                .fx(StatusEffects.SPEED, 1).fx(StatusEffects.HASTE, 0).build(),
            new Builder("Апатия", "Ничего не хочется. Совсем ничего.")
                .fx(StatusEffects.SLOWNESS, 1).fx(StatusEffects.WEAKNESS, 1).fx(StatusEffects.MINING_FATIGUE, 0).build(),
            new Builder("Эмоциональное выгорание", "Ресурс исчерпан.")
                .fx(StatusEffects.WEAKNESS, 1).fx(StatusEffects.SLOWNESS, 1).fx(StatusEffects.HUNGER, 0).build(),
            new Builder("Дереализация", "Мир ненастоящий. Ты это понял, да?")
                .fxD(StatusEffects.NAUSEA, 500, 0).fx(StatusEffects.SLOWNESS, 0).build(),
            new Builder("Деперсонализация", "Ты смотришь на себя со стороны.")
                .fxD(StatusEffects.NAUSEA, 500, 0).fxD(StatusEffects.BLINDNESS, 200, 0).build(),
            new Builder("Парасомния", "Ты не спишь, но и не бодрствуешь.")
                .fxD(StatusEffects.DARKNESS, 300, 0).fx(StatusEffects.SLOWNESS, 0).build(),

            // ---------- С галлюцинациями (15) ----------
            new Builder("Шизофрения", "Они здесь. Они всегда были здесь.")
                .fx(StatusEffects.SLOWNESS, 0)
                .hall("vex", "vex", "vex", "phantom", "enderman")
                .snd("entity.vex.ambient", "entity.ghast.scream").build(),
            new Builder("Острый психоз", "Зомби выходят из стен. Их нет, но они выходят.")
                .fxD(StatusEffects.NAUSEA, 600, 0)
                .hall("zombie", "zombie", "spider", "spider")
                .snd("entity.zombie.ambient").build(),
            new Builder("Посттравматическое расстройство", "Воспоминания догоняют. Слышишь сердцебиение?")
                .fxD(StatusEffects.BLINDNESS, 200, 0)
                .hall("phantom")
                .snd("entity.warden.heartbeat", "ambient.cave").build(),
            new Builder("Диссоциативное расстройство", "Тени повторяют твои движения.")
                .hall("vex", "vex")
                .snd("entity.phantom.ambient").build(),
            new Builder("Синдром Капгра", "Этот житель — подделка. Он повторяет за тобой.")
                .hall("villager")
                .snd("ambient.cave").build(),
            new Builder("Параноидный бред", "Пауки на стенах. Их нельзя ударить. Ты знал это.")
                .fx(StatusEffects.SLOWNESS, 0)
                .hall("spider", "spider", "spider")
                .snd("entity.spider.ambient").build(),
            new Builder("Лихорадочные видения", "Скелеты горят, но не сгорают.")
                .fxD(StatusEffects.NAUSEA, 400, 0)
                .hall("skeleton", "skeleton", "blaze")
                .snd("entity.skeleton.ambient").build(),
            new Builder("Ночные кошмары", "Не спи. Они ждут, когда ты закроешь глаза.")
                .fxD(StatusEffects.DARKNESS, 600, 0)
                .hall("phantom", "phantom", "enderman")
                .snd("entity.phantom.ambient", "entity.enderman.scream").build(),
            new Builder("Слуховые галлюцинации", "Голоса. Столько голосов.")
                .fxD(StatusEffects.NAUSEA, 300, 0)
                .snd("entity.ghast.scream", "ambient.cave", "entity.vex.ambient").build(),
            new Builder("Интоксикационный психоз", "Шипение с каждым шагом.")
                .hall("creeper", "creeper")
                .snd("entity.creeper.hurt").build(),
            new Builder("Шизоидный психоз", "Голоса, тени и голос внутри: «Убей их всех».")
                .fx(StatusEffects.WEAKNESS, 0).fxD(StatusEffects.DARKNESS, 300, 0)
                .hall("vex", "vex", "phantom", "enderman")
                .snd("entity.vex.ambient", "entity.ghast.scream")
                .task(TaskType.KILL_MOBS, 5, 90, pen(StatusEffects.WEAKNESS, 2), pen(StatusEffects.SLOWNESS, 2)).build(),
            new Builder("Мнимый преследователь", "Кто-то идёт за тобой. Каждый шаг. Каждый вздох.")
                .hall("enderman", "enderman")
                .snd("entity.enderman.scream").build(),
            new Builder("Демонофобия", "Они несутся к тебе из ниоткуда.")
                .fx(StatusEffects.SLOWNESS, 0)
                .hall("zombie", "zombie", "zombie")
                .snd("entity.zombie.ambient").build(),
            new Builder("Автофобия", "Ты один. Это самое страшное.")
                .fxD(StatusEffects.DARKNESS, 300, 0).fx(StatusEffects.SLOWNESS, 0)
                .snd("ambient.cave", "entity.ghast.scream").build(),
            new Builder("Синдром Стендаля", "Мир слишком красив, чтобы быть настоящим.")
                .fxD(StatusEffects.NAUSEA, 200, 0)
                .hall("villager", "villager")
                .snd("ambient.cave").build(),

            // ---------- С заданиями на время (15) ----------
            new Builder("Синдром самурая", "Клинок жаждет крови. Кровь жаждет клинка.")
                .fx(StatusEffects.STRENGTH, 2)
                .task(TaskType.KILL_MOBS, 5, 90, pen(StatusEffects.WEAKNESS, 2), pen(StatusEffects.SLOWNESS, 2)).build(),
            new Builder("Клептомания", "Руки сами тянутся к чужому. Просто сломай что-нибудь.")
                .fx(StatusEffects.SPEED, 0)
                .task(TaskType.BREAK_BLOCKS, 16, 60, pen(StatusEffects.SLOWNESS, 2), pen(StatusEffects.NAUSEA, 0)).build(),
            new Builder("Обсессивно-компульсивное расстройство", "Ровно 32. Не 31. Не 33. РОВНО 32.")
                .task(TaskType.BREAK_BLOCKS, 32, 90, pen(StatusEffects.NAUSEA, 1), pen(StatusEffects.MINING_FATIGUE, 2)).build(),
            new Builder("Адреналиновая зависимость", "Тебе нужен выброс. Прямо сейчас.")
                .fx(StatusEffects.SPEED, 1)
                .task(TaskType.KILL_MOBS, 10, 120, pen(StatusEffects.WEAKNESS, 2), pen(StatusEffects.SLOWNESS, 2), pen(StatusEffects.NAUSEA, 0)).build(),
            new Builder("Пиромания", "Огонь. Нужен огонь. Или просто ритм.")
                .fx(StatusEffects.HASTE, 1)
                .task(TaskType.BREAK_BLOCKS, 24, 90, pen(StatusEffects.NAUSEA, 1), pen(StatusEffects.SLOWNESS, 1)).build(),
            new Builder("Гиперкинетическое расстройство", "Энергия рвётся наружу. Двигайся!")
                .fx(StatusEffects.SPEED, 2)
                .task(TaskType.BREAK_BLOCKS, 16, 60, pen(StatusEffects.SLOWNESS, 2)).build(),
            new Builder("Компульсия агрессии", "Дерись. Дерись. ДЕРИСЬ.")
                .fx(StatusEffects.STRENGTH, 1)
                .task(TaskType.KILL_MOBS, 8, 90, pen(StatusEffects.WEAKNESS, 1), pen(StatusEffects.NAUSEA, 0)).build(),
            new Builder("Гипернастороженность", "Никто не должен тебя коснуться. Никто.")
                .fx(StatusEffects.SLOWNESS, 0)
                .task(TaskType.TAKE_NO_DAMAGE, 1, 60, pen(StatusEffects.BLINDNESS, 0), pen(StatusEffects.SLOWNESS, 1)).build(),
            new Builder("Хладнокровие", "Один удар — и всё рассыпается. Не позволяй.")
                .task(TaskType.TAKE_NO_DAMAGE, 1, 90, pen(StatusEffects.NAUSEA, 1), pen(StatusEffects.WEAKNESS, 1)).build(),
            new Builder("Апатичный ступор", "Сделай хоть что-нибудь. Хотя бы один блок.")
                .fx(StatusEffects.SLOWNESS, 1)
                .task(TaskType.BREAK_BLOCKS, 8, 60, pen(StatusEffects.MINING_FATIGUE, 2), pen(StatusEffects.WEAKNESS, 1)).build(),
            new Builder("Одержимость порядком", "Всё должно быть разрушено по расписанию.")
                .fx(StatusEffects.HASTE, 0)
                .task(TaskType.BREAK_BLOCKS, 24, 90, pen(StatusEffects.MINING_FATIGUE, 2), pen(StatusEffects.SLOWNESS, 1)).build(),
            new Builder("Триумфализм", "Победы и только победы. Докажи.")
                .fx(StatusEffects.STRENGTH, 1).fx(StatusEffects.SPEED, 1)
                .task(TaskType.KILL_MOBS, 5, 60, pen(StatusEffects.WEAKNESS, 1), pen(StatusEffects.SLOWNESS, 1)).build(),
            new Builder("Мания преследования", "Охотник становится жертвой. Или наоборот.")
                .hall("enderman")
                .task(TaskType.KILL_MOBS, 3, 60, pen(StatusEffects.SLOWNESS, 2), pen(StatusEffects.BLINDNESS, 0)).build(),
            new Builder("Оборонительный тран", "Замирай. Не двигайся. Не привлекай внимания.")
                .fx(StatusEffects.SLOWNESS, 0)
                .task(TaskType.TAKE_NO_DAMAGE, 1, 45, pen(StatusEffects.BLINDNESS, 0), pen(StatusEffects.WEAKNESS, 1)).build(),
            new Builder("Компульсия строителя", "Руки должны ломать. Это единственное лекарство.")
                .fx(StatusEffects.HASTE, 1)
                .task(TaskType.BREAK_BLOCKS, 32, 120, pen(StatusEffects.MINING_FATIGUE, 2), pen(StatusEffects.SLOWNESS, 1)).build(),

            // ---------- Дополнительные диагнозы (25) ----------
            new Builder("Агорафобия", "Открытое пространство кажется опасным.")
                .fx(StatusEffects.SLOWNESS, 0).fxD(StatusEffects.NAUSEA, 300, 0).build(),
            new Builder("Эметофобия", "Тошнота не отпускает.")
                .fxD(StatusEffects.NAUSEA, 800, 1).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Монофобия", "Одиночество давит сильнее тьмы.")
                .fxD(StatusEffects.DARKNESS, 300, 0).fx(StatusEffects.SLOWNESS, 0).build(),
            new Builder("Кенофобия", "Пустота вокруг становится невыносимой.")
                .fx(StatusEffects.WEAKNESS, 1).fxD(StatusEffects.NAUSEA, 300, 0).build(),
            new Builder("Атаксия", "Шаги становятся неуверенными.")
                .fx(StatusEffects.SLOWNESS, 2).fxD(StatusEffects.NAUSEA, 200, 0).build(),
            new Builder("Сенсорная перегрузка", "Каждый сигнал мира слишком громкий.")
                .fxD(StatusEffects.DARKNESS, 200, 0).fxD(StatusEffects.NAUSEA, 500, 0)
                .snd("block.bell.use", "ambient.cave").build(),
            new Builder("Фобия глубины", "Под ногами слишком много пустоты.")
                .fx(StatusEffects.SLOWNESS, 1).fxD(StatusEffects.BLINDNESS, 100, 0).build(),
            new Builder("Страх высоты", "Не смотри вниз.")
                .fxD(StatusEffects.NAUSEA, 500, 0).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Навязчивые мысли", "Одна мысль возвращается снова и снова.")
                .fx(StatusEffects.MINING_FATIGUE, 1).fx(StatusEffects.SLOWNESS, 0).build(),
            new Builder("Психомоторное возбуждение", "Невозможно усидеть на месте.")
                .fx(StatusEffects.SPEED, 2).fx(StatusEffects.JUMP_BOOST, 1).fx(StatusEffects.HUNGER, 0).build(),
            new Builder("Психомоторная заторможенность", "Даже простое движение требует усилий.")
                .fx(StatusEffects.SLOWNESS, 2).fx(StatusEffects.MINING_FATIGUE, 1).build(),
            new Builder("Нарушение восприятия времени", "Секунды растягиваются бесконечно.")
                .fxD(StatusEffects.NAUSEA, 400, 0).fx(StatusEffects.SLOWNESS, 1)
                .snd("block.bell.use").build(),
            new Builder("Иллюзия наблюдения", "Кто-то смотрит из темноты.")
                .fx(StatusEffects.DARKNESS, 0).hall("enderman", "phantom")
                .snd("entity.enderman.stare").build(),
            new Builder("Зрительные искажения", "Контуры предметов расплываются.")
                .fxD(StatusEffects.NAUSEA, 700, 0).hall("vex", "phantom").build(),
            new Builder("Слуховая настороженность", "Каждый шорох звучит как угроза.")
                .fx(StatusEffects.WEAKNESS, 0).snd("entity.creeper.primed", "ambient.cave").build(),
            new Builder("Навязчивый страх заражения", "Кажется, опасность повсюду.")
                .fx(StatusEffects.HUNGER, 1).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Страх темноты", "Тени становятся слишком длинными.")
                .fx(StatusEffects.DARKNESS, 0).hall("phantom").build(),
            new Builder("Страх мобов", "Любой шорох напоминает о монстрах.")
                .fx(StatusEffects.SLOWNESS, 1).hall("zombie", "spider").build(),
            new Builder("Мания контроля", "Всё должно быть под контролем.")
                .fx(StatusEffects.HASTE, 1).fx(StatusEffects.MINING_FATIGUE, 0).build(),
            new Builder("Импульсивность", "Сначала действие, потом мысли.")
                .fx(StatusEffects.SPEED, 1).fx(StatusEffects.WEAKNESS, 0).build(),
            new Builder("Истощение", "Сил почти не осталось.")
                .fx(StatusEffects.WEAKNESS, 2).fx(StatusEffects.HUNGER, 1).build(),
            new Builder("Тревожное ожидание", "Сейчас обязательно что-то случится.")
                .fxD(StatusEffects.NAUSEA, 500, 0).fx(StatusEffects.SLOWNESS, 0)
                .snd("entity.warden.heartbeat").build(),
            new Builder("Паническое избегание", "Хочется бежать от всего вокруг.")
                .fx(StatusEffects.SPEED, 1).fxD(StatusEffects.BLINDNESS, 100, 0).build(),
            new Builder("Навязчивая проверка", "Проверь ещё раз. И ещё.")
                .fx(StatusEffects.MINING_FATIGUE, 1)
                .task(TaskType.BREAK_BLOCKS, 12, 90, pen(StatusEffects.SLOWNESS, 1)).build(),
            new Builder("Синдром постоянной угрозы", "Опасность не исчезает ни на секунду.")
                .fx(StatusEffects.WEAKNESS, 1).fx(StatusEffects.SLOWNESS, 0)
                .hall("phantom", "enderman").snd("entity.warden.heartbeat").build(),

            // ---------- Атмосферные (10) ----------
            new Builder("Танатофобия", "Смерть уже идёт. Ты слышишь шаги.")
                .fx(StatusEffects.SLOWNESS, 0)
                .snd("entity.warden.heartbeat", "ambient.cave").build(),
            new Builder("Гемофобия", "Кровь. Даже своя — слишком много.")
                .fxD(StatusEffects.NAUSEA, 200, 0)
                .snd("ambient.cave").build(),
            new Builder("Дежавю", "Это уже было. Или будет. Или ничего этого нет.")
                .fxD(StatusEffects.NAUSEA, 200, 0)
                .snd("block.bell.use", "ambient.cave").build(),
            new Builder("Сонный паралич", "Не можешь пошевелиться. Что-то сидит на груди.")
                .fxD(StatusEffects.DARKNESS, 200, 0).fx(StatusEffects.SLOWNESS, 3)
                .snd("entity.ghast.scream").build(),
            new Builder("Замедленное время", "Мир тягучий. Как мёд. Как смола.")
                .fx(StatusEffects.SLOWNESS, 1).fxD(StatusEffects.NAUSEA, 300, 0)
                .snd("ambient.cave").build(),
            new Builder("Фоногенные галлюцинации", "Звуки, которых нет. Но они есть.")
                .fxD(StatusEffects.NAUSEA, 200, 0)
                .snd("entity.ghast.scream", "entity.zombie.ambient", "ambient.cave").build(),
            new Builder("Ночная тоска", "Луна давит на плечи.")
                .fxD(StatusEffects.DARKNESS, 600, 0).fx(StatusEffects.SLOWNESS, 0)
                .snd("ambient.cave").build(),
            new Builder("Мрачные мысли", "Они приходят, когда тихо. Пусть будет громко.")
                .fx(StatusEffects.WEAKNESS, 0).fxD(StatusEffects.DARKNESS, 300, 0)
                .snd("ambient.cave", "entity.warden.heartbeat").build(),
            new Builder("Гиперакузия", "Каждый звук — как удар колокола.")
                .fxD(StatusEffects.NAUSEA, 200, 0)
                .snd("block.bell.use", "ambient.cave").build(),
            new Builder("Полный психоз", "Всё сразу. Голоса. Тени. Приказы. Никакого спасения.")
                .fx(StatusEffects.WEAKNESS, 1).fxD(StatusEffects.DARKNESS, 600, 0)
                .hall("vex", "vex", "phantom", "zombie")
                .snd("entity.vex.ambient", "entity.ghast.scream", "ambient.cave")
                .task(TaskType.KILL_MOBS, 8, 90, pen(StatusEffects.WEAKNESS, 2), pen(StatusEffects.SLOWNESS, 2), pen(StatusEffects.NAUSEA, 1)).build()
        );
    }
}
