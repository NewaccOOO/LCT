package ru.lct.heatnet.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import lombok.Value;

public final class Rules {
    private static final String FALLBACK = "_fallback";
    public static final String RULES_PROPERTY = "heatnet.rules";
    private static final String RULES_ENV = "HEATNET_RULES";
    private static final String CLASSPATH = "classpath:";

    private final List<Diameter> diameters;
    private final List<ChamberPrice> chamberPrices;
    private final double tieInCost;
    private final ChamberRule chamberRule;
    private final double penaltyFixed;
    private final double penaltyPerTph;
    private final double scoreCostBase;
    private final double scoreLengthBaseM;
    private final double scoreWCost;
    private final double scoreWLength;
    private final Map<String, RestrictionRule> restrictions;
    private final RestrictionRule fallback;

    @Value
    private static class ChamberPrice {
        int dnMin;
        int dnMax;
        double cost;
    }

    private Rules(JsonNode root) {
        List<Diameter> table = new ArrayList<>();
        for (JsonNode row : root.required("diameters")) {
            table.add(new Diameter((int) number(row, "dn"), number(row, "capacity_tph"), number(row, "max_length_m"),
                    number(row, "new_rub_m"), number(row, "recon_rub_m"), number(row, "width_m"), number(row, "height_m")));
        }
        table.sort(Comparator.comparingInt(Diameter::getDn));
        diameters = List.copyOf(table);

        List<ChamberPrice> prices = new ArrayList<>();
        for (JsonNode row : root.required("chamber_cost")) {
            prices.add(new ChamberPrice((int) number(row, "dn_min"), (int) number(row, "dn_max"), number(row, "cost")));
        }
        chamberPrices = List.copyOf(prices);

        tieInCost = number(root, "tie_in_cost");
        JsonNode chamber = root.required("chamber_rule");
        chamberRule = new ChamberRule(number(chamber, "max_dist_m"), (int) number(chamber, "max_segments"),
                (int) number(chamber, "max_branches"));
        JsonNode penalty = root.required("penalty");
        penaltyFixed = number(penalty, "fixed");
        penaltyPerTph = number(penalty, "per_tph");
        JsonNode score = root.required("score");
        scoreCostBase = number(score, "cost_base");
        scoreLengthBaseM = number(score, "length_base_m");
        scoreWCost = number(score, "w_cost");
        scoreWLength = number(score, "w_length");

        Map<String, RestrictionRule> byType = new HashMap<>();
        JsonNode restrictionsNode = root.required("restrictions");
        Iterator<Map.Entry<String, JsonNode>> fields = restrictionsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().startsWith("_")) {
                byType.put(field.getKey(), restrictionRule(field.getKey(), field.getValue()));
            }
        }
        restrictions = Map.copyOf(byType);
        fallback = restrictionRule(FALLBACK, restrictionsNode.required(FALLBACK));
    }

    /**
     * Правила расчёта. Файл задаётся свойством {@code heatnet.rules} или переменной {@code HEATNET_RULES}: путь на диске
     * или {@code classpath:/…} для файла внутри jar. Без них — {@code rules.json} кейса тепловых сетей. Так тот же
     * сервис считает другой ресурс, например водопровод, без изменения кода.
     */
    public static Rules load() {
        String source = System.getProperty(RULES_PROPERTY, System.getenv(RULES_ENV));
        if (source == null || source.isBlank()) {
            source = CLASSPATH + "/rules.json";
        }
        try (InputStream in = source.startsWith(CLASSPATH)
                ? Rules.class.getResourceAsStream(source.substring(CLASSPATH.length()))
                : Files.newInputStream(Path.of(source))) {
            if (in == null) {
                throw new IllegalStateException("Файл правил не найден: " + source);
            }
            return new Rules(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать файл правил " + source, e);
        }
    }

    public double maxCapacityTph() {
        return diameters.get(diameters.size() - 1).getCapacityTph();
    }

    public Diameter diameterFor(double flowTph) {
        for (Diameter diameter : diameters) {
            if (diameter.getCapacityTph() >= flowTph) {
                return diameter;
            }
        }
        throw new IllegalArgumentException("Расход " + flowTph + " т/ч больше пропускной способности наибольшего диаметра");
    }

    /** Наименьший диаметр, предельная длина которого не меньше lengthM; null — таких нет. */
    public Diameter diameterForLength(double lengthM) {
        for (Diameter diameter : diameters) {
            if (diameter.getMaxLengthM() >= lengthM) {
                return diameter;
            }
        }
        return null;
    }

    public Diameter diameter(int dn) {
        for (Diameter diameter : diameters) {
            if (diameter.getDn() == dn) {
                return diameter;
            }
        }
        throw new IllegalArgumentException("Диаметра DN" + dn + " нет в таблице");
    }

    public List<Diameter> diameters() {
        return diameters;
    }

    public Diameter nextDiameter(int dn) {
        for (Diameter diameter : diameters) {
            if (diameter.getDn() > dn) {
                return diameter;
            }
        }
        return null;
    }

    public double chamberCost(int dn) {
        for (ChamberPrice price : chamberPrices) {
            if (price.getDnMin() <= dn && dn <= price.getDnMax()) {
                return price.getCost();
            }
        }
        throw new IllegalArgumentException("Стоимость камеры для DN" + dn + " не задана");
    }

    public double tieInCost() {
        return tieInCost;
    }

    public double penalty(double flowTph) {
        return penaltyFixed + penaltyPerTph * flowTph;
    }

    /** Правило типа ограничения; тип, которого нет в справочнике, получает правило {@code _fallback}. */
    public RestrictionRule restriction(String type) {
        return restrictions.getOrDefault(type, fallback);
    }

    public boolean isKnown(String type) {
        return restrictions.containsKey(type);
    }

    public ChamberRule chamberRule() {
        return chamberRule;
    }

    /**
     * Сколько рублей стоимости весит один метр длины в S: (w_length / length_base) / (w_cost / cost_base). Нужен,
     * чтобы переводить разовые расходы в метры трассы при сравнении присоединений.
     */
    public double lengthWorthRub() {
        return (scoreWLength / scoreLengthBaseM) / (scoreWCost / scoreCostBase);
    }

    public double score(double cost, double length) {
        return scoreWCost * (cost / scoreCostBase) + scoreWLength * (length / scoreLengthBaseM);
    }

    private static RestrictionRule restrictionRule(String type, JsonNode node) {
        String rule = node.required("rule").asText();
        if (!rule.equals("forbid") && !rule.equals("special")) {
            throw new IllegalStateException("rules.json: у " + type + " неизвестное правило " + rule);
        }
        NavigableMap<Integer, Double> clearance = new TreeMap<>();
        JsonNode clearanceNode = node.required("clearance_m");
        if (clearanceNode.isArray()) {
            for (JsonNode tier : clearanceNode) {
                clearance.put((int) number(tier, "dn_max"), number(tier, "m"));
            }
        } else {
            clearance.put(Integer.MAX_VALUE, number(node, "clearance_m"));
        }
        return new RestrictionRule(type, rule.equals("forbid"), clearance, optionalNumber(node, "min_angle_deg"),
                optionalNumber(node, "margin_m"), optionalNumber(node, "k_special"), optionalNumber(node, "half_width_m"));
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isNumber()) {
            throw new IllegalStateException("rules.json: поле " + field + " должно быть числом");
        }
        return value.doubleValue();
    }

    private static Double optionalNumber(JsonNode node, String field) {
        return node.has(field) ? number(node, field) : null;
    }
}
