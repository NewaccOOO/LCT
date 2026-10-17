package ru.lct.heatnet.model;

import lombok.Value;

/**
 * Пересечение участка с существующей коммуникацией в режиме с глубиной (приложение ДИТ «Трассировка с учётом
 * глубины», п. 7.3–7.4): тип объекта, прохождение над (over) или под (under) ним и вертикальный просвет между гранями
 * габаритов, м.
 */
@Value
public class DepthCrossing {
    String type;
    String position;
    double clearanceM;
}
