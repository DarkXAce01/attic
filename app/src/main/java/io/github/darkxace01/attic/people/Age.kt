package io.github.darkxace01.attic.people

import io.github.darkxace01.attic.data.PhotoDate
import java.time.Period

/**
 * How old someone was in a photo: whole years from their birth to the photo's date. "About" when either
 * date is loose enough (more than a year wide) that it could be a year off. Null before they were born.
 */
data class Age(val years: Int, val about: Boolean) {
    val label: String get() = when {
        years == 0 -> if (about) "A baby" else "Under 1"
        about -> "About $years"
        else -> "Age $years"
    }

    companion object {
        fun at(born: PhotoDate, photo: PhotoDate): Age? {
            val years = Period.between(born.representative, photo.representative).years
            if (photo.to.isBefore(born.from)) return null
            val loose = born.to.toEpochDay() - born.from.toEpochDay() > 366 || photo.to.toEpochDay() - photo.from.toEpochDay() > 366
            return Age(years.coerceAtLeast(0), loose)
        }
    }
}
