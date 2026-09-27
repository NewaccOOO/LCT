package ru.lct.heatnet.model;

import java.util.List;
import java.util.Set;
import lombok.Value;

@Value
public class InputData {
    /** Источники теплоснабжения: у каждой системы города свой, приложение 18.09 число не ограничивает. */
    List<Source> sources;
    List<NetworkSegment> segments;
    List<Chamber> chambers;
    List<FutureOks> futureOks;
    List<ConnectionPoint> connectionPoints;
    List<ExistingOks> existingOks;
    List<Restriction> restrictions;
    List<Diagnostic> diagnostics;
    /** Готовые строки предупреждений для человека: вход прочитан, но часть объектов обработана по умолчанию. */
    List<String> warnings;
    /** ID точек подключения и камер, записанные во входе числом. */
    Set<String> numericIds;
}
