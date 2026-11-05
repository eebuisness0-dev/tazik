package dev.tazik.inferno.engine

/**
 * Отдаёт края экрана гостю.
 *
 * Гость — тоже iPhone, и его жесты начинаются с тех же краёв, что у хоста:
 * свайп от низа уводит на домашний экран хоста задолго до того, как его
 * увидит гость. В Swift это лечилось
 * `preferredScreenEdgesDeferringSystemGestures`: система просила второй
 * свайп, и первый доставался гостю.
 *
 * На Android такого разделения нет, но EmulatorActivity уже держит
 * immersive-режим (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`): системные панели
 * спрятаны, первый свайп от края достаётся гостю, а второй показывает панели
 * хоста. Тот же результат, что у отложенных жестов, — поэтому здесь делать
 * нечего.
 */
object SystemGestures {

    /** Прочитанный флаг; меняется через `apply`. */
    @Volatile
    var deferEdges: Boolean = false

    fun apply(deferEdges: Boolean) {
        this.deferEdges = deferEdges
    }
}
