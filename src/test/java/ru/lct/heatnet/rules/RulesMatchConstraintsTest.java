package ru.lct.heatnet.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RulesMatchConstraintsTest {
    private static final String NUM = "(\\d[\\d \\u00a0]*(?:,\\d+)?)";
    private static final double EPS = 1e-9;
    private static final Rules RULES = Rules.load();
    private static String constraints;

    @BeforeAll
    static void readConstraints() throws IOException {
        constraints = Files.readString(Path.of("architecture/CONSTRAINTS.md"));
    }

    @Test
    void diameterTableMatches() {
        List<List<String>> rows = table("### Таблица диаметров");
        assertEquals(rows.size(), RULES.diameters().size(), "число диаметров");
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            Diameter diameter = RULES.diameters().get(i);
            String name = "DN" + row.get(0);
            assertEquals((int) number(row.get(0)), diameter.getDn(), name);
            assertEquals(number(row.get(1)), diameter.getCapacityTph(), EPS, name + " capacityTph");
            assertEquals(number(row.get(2)), diameter.getMaxLengthM(), EPS, name + " maxLengthM");
            assertEquals(number(row.get(3)), diameter.getNewRubM(), EPS, name + " newRubM");
            assertEquals(number(row.get(4)), diameter.getReconRubM(), EPS, name + " reconRubM");
        }
    }

    @Test
    void pipeSizesMatch() {
        List<List<String>> rows = table("### Расчётные габариты пары труб");
        assertEquals(rows.size(), RULES.diameters().size(), "число строк габаритов");
        for (List<String> row : rows) {
            Diameter diameter = RULES.diameter((int) number(row.get(0)));
            assertEquals(number(row.get(3)), diameter.getWidthM(), EPS, "DN" + row.get(0) + " widthM");
            assertEquals(number(row.get(4)), diameter.getHeightM(), EPS, "DN" + row.get(0) + " heightM");
        }
    }

    @Test
    void chamberCostsMatch() {
        List<List<String>> rows = table("### Камеры и врезки");
        for (Diameter diameter : RULES.diameters()) {
            int dn = diameter.getDn();
            List<String> range = rows.stream()
                    .filter(row -> {
                        String[] bounds = row.get(0).split("–");
                        return number(bounds[0]) <= dn && dn <= number(bounds[1]);
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("DN" + dn + " не попал ни в один диапазон камер"));
            assertEquals(number(range.get(1)), RULES.chamberCost(dn), EPS, "стоимость камеры DN" + dn);
        }
    }

    @Test
    void restrictionTableMatches() {
        List<List<String>> rows = table("## 7. Пространственные ограничения");
        assertEquals(10, rows.size(), "число типов ограничений");
        for (List<String> row : rows) {
            String type = find("`(\\w+)`", row.get(0));
            RestrictionRule rule = RULES.restriction(type);
            assertEquals(row.get(1).contains("запрещено"), rule.forbid(), type + " forbid");
            for (Diameter diameter : RULES.diameters()) {
                int dn = diameter.getDn();
                double expected = type.equals("oks_existing")
                        ? oksExistingClearance(row.get(2), dn)
                        : number(matcher(NUM + " м", row.get(2)).group(1));
                assertEquals(expected, rule.clearanceM(dn), EPS, type + " clearanceM DN" + dn);
            }
            assertOptional(find("не менее (\\d+)°", row.get(3)), rule.getMinAngleDeg(), type + " minAngleDeg");
            assertOptional(find("(?:плюс|по) " + NUM + " м", row.get(5)), rule.getMarginM(), type + " marginM");
            assertOptional(find(NUM, row.get(6)), rule.getKSpecial(), type + " kSpecial");
        }
    }

    private static double oksExistingClearance(String cell, int dn) {
        String[] tiers = cell.split(";");
        Matcher below = matcher("(\\d+) м при .*до (\\d+) мм", tiers[0]);
        Matcher between = matcher("(\\d+) м при (\\d+)–(\\d+) мм", tiers[1]);
        Matcher above = matcher("(\\d+) м при (\\d+) мм и более", tiers[2]);
        if (dn < number(below.group(2))) {
            return number(below.group(1));
        }
        if (number(between.group(2)) <= dn && dn <= number(between.group(3))) {
            return number(between.group(1));
        }
        if (dn >= number(above.group(2))) {
            return number(above.group(1));
        }
        return fail("DN" + dn + " не попал ни в один диапазон отступа oks_existing");
    }

    private static void assertOptional(String expected, Double actual, String name) {
        if (expected == null) {
            assertNull(actual, name);
        } else {
            assertEquals(number(expected), actual, EPS, name);
        }
    }

    private static List<List<String>> table(String heading) {
        int start = constraints.indexOf(heading);
        assertTrue(start >= 0, "в CONSTRAINTS.md нет раздела " + heading);
        List<List<String>> rows = new ArrayList<>();
        for (String line : constraints.substring(start).lines().skip(1).collect(Collectors.toList())) {
            if (line.startsWith("|")) {
                String trimmed = line.trim();
                rows.add(Arrays.stream(trimmed.substring(1, trimmed.length() - 1).split("\\|"))
                        .map(String::trim)
                        .collect(Collectors.toList()));
            } else if (!rows.isEmpty()) {
                break;
            }
        }
        return rows.subList(2, rows.size());
    }

    private static Matcher matcher(String regex, String text) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), "«" + text + "» не совпало с " + regex);
        return matcher;
    }

    private static String find(String regex, String text) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static double number(String text) {
        return Double.parseDouble(text.replaceAll("[\\s\\u00a0]", "").replace(',', '.'));
    }
}
