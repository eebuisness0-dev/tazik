package dev.tazik.inferno.engine

/**
 * На ком стоит приложение и что сделал каждый.
 *
 * Аватары берутся с GitHub (`github.com/<login>.png`), а не с X. X пробовали
 * первым, и он не работает: открытого конца для аватарок у него нет, а
 * обычный сторонний развязыватель отвечает заглушкой на 569 байт вместо
 * фотографии. GitHub отдаёт настоящую картинку, от первого лица и без ключа,
 * так что источник — он; X остаётся ссылкой на карточке, и только у тех, чей
 * ник известен на самом деле, — выдуманный ник хуже отсутствующего.
 *
 * Открытие этого экрана тянет аватары с github.com. Больше с телефона не
 * уходит ничего.
 */
data class Credit(
    /** Логин на GitHub, там же, откуда аватар. Пустой у записей, которые не
     * человек, — QEMU это тысячи людей.
     */
    val github: String,
    val name: String,
    /** Что он сделал, в одно-два предложения. Написано, чтобы быть правдой, а
     * не приятным.
     */
    val did: String,
    /** Только когда ник известен на самом деле. */
    val x: String? = null,
    val site: String? = null,
) {
    val id: String get() = github.ifEmpty { name }

    /** Просится крупным: на карточке картинка — фон, а не миниатюра, и
     * рисуется во всю ширину листа. GitHub меняет размер по запросу и отдаёт
     * оригинал, когда тот меньше.
     */
    val avatarUrl: String?
        get() = if (github.isEmpty()) null else "https://github.com/$github.png?size=800"

    val githubUrl: String? get() = if (github.isEmpty()) null else "https://github.com/$github"
    val xUrl: String? get() = x?.let { "https://x.com/$it" }
    val siteUrl: String? get() = site

    companion object {
        /** Список, в порядке, как ложится работа: сначала эмулятор, потому что
         * без него показывать нечего, и двое, кто написал это приложение, в
         * конце.
         */
        val all: List<Credit> = listOf(
            Credit(
                github = "VisualEhrmanntraut",
                name = "Visual Ehrmanntraut",
                did = L("Автор Inferno — эмулятора Apple Silicon, внутри которого и работает гость. Всё, что делает это приложение, стоит на нём."),
                x = "HeWhomCodes",
                site = "https://chefkiss.dev",
            ),
            Credit(
                github = "ChefKissInc",
                name = "ChefKiss",
                did = L("Команда Inferno. Их же руководства по сборке образа гостя и по джейлбрейк-бутстрапу — без них запустить тут было бы нечего."),
                x = null,
                site = "https://chefkiss.dev",
            ),
            Credit(
                github = "",
                name = "QEMU",
                did = L("Inferno — форк QEMU, а QEMU написан очень многими людьми. Отдельного человека тут назвать нельзя, но без их работы не было бы ни эмулятора, ни приложения."),
                x = null,
                site = "https://www.qemu.org",
            ),
            // Аватар и ссылка идут организации, потому что там есть картинку
            // взять; у самого Клода аккаунта на GitHub нет.
            Credit(
                github = "anthropics",
                name = "Claude",
                did = L("Сделал всю работу."),
                x = null,
                site = "https://claude.ai",
            ),
            Credit(
                github = "MakrSas",
                name = "Makr",
                did = L("Управлял Клодом."),
                x = "makrotasuss",
                site = null,
            ),
        )
    }
}
