package io.beatmaps.common.beatsaber

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.beatmaps.common.api.ECharacteristic
import io.beatmaps.common.api.EDifficulty
import io.beatmaps.common.api.searchEnum
import io.beatmaps.common.copyTo
import io.beatmaps.common.zip.ExtractedInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import net.coobird.thumbnailator.Thumbnails
import org.jaudiotagger.audio.generic.GenericAudioHeader
import org.jaudiotagger.audio.ogg.OggFileReader
import org.valiktor.Constraint
import org.valiktor.ConstraintViolation
import org.valiktor.DefaultConstraintViolation
import org.valiktor.Validator
import org.valiktor.constraints.In
import org.valiktor.constraints.NotNull
import org.valiktor.functions.isBetween
import org.valiktor.functions.isEqualTo
import org.valiktor.functions.isIn
import org.valiktor.functions.isNotBlank
import org.valiktor.functions.isNotNull
import org.valiktor.functions.isPositiveOrZero
import org.valiktor.functions.isZero
import org.valiktor.functions.matches
import org.valiktor.functions.validate
import org.valiktor.functions.validateForEach
import org.valiktor.validate
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import kotlin.io.path.inputStream

data class MapInfo(
    val _version: String,
    val _songName: String,
    val _songSubName: String,
    val _songAuthorName: String,
    val _levelAuthorName: String,
    val _beatsPerMinute: Float,
    val _shuffle: Float,
    val _shufflePeriod: Float,
    val _previewStartTime: Float,
    val _previewDuration: Float,
    val _songFilename: String,
    val _coverImageFilename: String,
    val _environmentName: String,
    val _allDirectionsEnvironmentName: String?,
    val _songTimeOffset: Float,
    val _customData: MapCustomData?,
    val _difficultyBeatmapSets: List<DifficultyBeatmapSet>
) {
    fun imageInfo(path: Path?, info: ExtractedInfo) = path?.inputStream().use { stream ->
        try {
            ImageIO.createImageInputStream(stream).use { iis ->
                val readers = ImageIO.getImageReaders(iis)
                if (readers.hasNext()) {
                    val reader = readers.next()
                    val format = reader.formatName
                    reader.input = iis
                    val image = reader.read(0)

                    val newImageStream = ByteArrayOutputStream()
                    Thumbnails
                        .of(image)
                        .size(256, 256)
                        .outputFormat("JPEG")
                        .outputQuality(0.8)
                        .toOutputStream(newImageStream)
                    info.thumbnail = newImageStream

                    ImageInfo(format.lowercase(), image.width, image.height)
                } else {
                    null
                }
            }
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun audioValid(audio: File, info: ExtractedInfo) =
        try {
            val header = OggFileReader().read(audio).audioHeader
            if (header is GenericAudioHeader) {
                info.duration = header.preciseLength
            } else {
                // May have been rounded down
                info.duration = (header.trackLength + 1).toFloat()
            }
        } catch (e: Exception) {
            try {
                val wavInfo = AudioSystem.getAudioInputStream(audio)
                info.duration = ((wavInfo.frameLength + 0.0) / wavInfo.format.frameRate).toFloat()
            } catch (e: Exception) {
                null
            }
        } != null && info.duration > 0

    fun validate(files: Set<String>, info: ExtractedInfo, audio: File, getFile: (String) -> Path?) = validate(this) {
        validate(MapInfo::_version).isNotNull().matches(Regex("\\d+\\.\\d+\\.\\d+"))
        validate(MapInfo::_songName).isNotNull().isNotBlank().validate(MetadataLength) {
            _songName.length + _levelAuthorName.length <= 100
        }
        validate(MapInfo::_beatsPerMinute).isNotNull().isBetween(10f, 1000f)
        validate(MapInfo::_previewStartTime).isPositiveOrZero()
        validate(MapInfo::_previewDuration).isPositiveOrZero()
        validate(MapInfo::_songFilename).isNotNull().validate(InFiles) { it == null || files.contains(it.lowercase()) }
            .validate(AudioFormat) { it == null || audioValid(audio, info) }
        val imageInfo = imageInfo(getFile(_coverImageFilename), info)
        validate(MapInfo::_coverImageFilename).isNotNull().validate(InFiles) { it == null || files.contains(it.lowercase()) }
            .validate(ImageFormat) { imageInfo != null && arrayOf("jpeg", "jpg", "png").contains(imageInfo.format) }
            .validate(ImageSquare) { imageInfo == null || imageInfo.width == imageInfo.height }
            .validate(ImageSize) { imageInfo == null || imageInfo.width >= 256 && imageInfo.height >= 256 }
        validate(MapInfo::_customData).validate {
            extraFieldsViolation(
                constraintViolations,
                it.additionalInformation.keys
            )
        }
        validate(MapInfo::_allDirectionsEnvironmentName).isEqualTo("GlassDesertEnvironment")
        validate(MapInfo::_songTimeOffset).isZero()
        validate(MapInfo::_difficultyBeatmapSets).validateForEach { it.validate(this, files, getFile, info) }
    }
}

data class ImageInfo(val format: String, val width: Int, val height: Int)

object InFiles : Constraint
object ImageSquare : Constraint
object ImageSize : Constraint
object ImageFormat : Constraint
object AudioFormat : Constraint
object CutDirection : Constraint
object MisplacedCustomData : Constraint
data class UniqueDiff(val diff: String) : Constraint
object MetadataLength : Constraint

data class MapCustomData(
    val _contributors: List<Contributor>?,
    val _editors: MapEditors?,
    @JsonIgnore @get:JsonAnyGetter val additionalInformation: LinkedHashMap<String, Any> = linkedMapOf()
) {
    @JsonAnySetter
    fun ignored(name: String, value: Any) {
        additionalInformation[name] = value
    }
}

data class MapEditors(
    val _lastEditedBy: String?,
    val beatSage: MapEditorVersion?,
    @get:JsonProperty("MMA2")
    val MMA2: MapEditorVersion?,
    @get:JsonProperty("ChroMapper")
    val ChroMapper: MapEditorVersion?,
    @JsonIgnore @get:JsonAnyGetter val additionalInformation: LinkedHashMap<String, Any> = linkedMapOf()
) {
    @JsonAnySetter
    fun ignored(name: String, value: Any) {
        additionalInformation[name] = value
    }
}

data class MapEditorVersion(
    val version: String,
    @JsonIgnore @get:JsonAnyGetter val additionalInformation: LinkedHashMap<String, Any> = linkedMapOf()
) {
    @JsonAnySetter
    fun ignored(name: String, value: Any) {
        additionalInformation[name] = value
    }
}

data class Contributor(
    val _role: String? = null,
    val _name: String? = null,
    val _iconPath: String? = null
)

data class DifficultyBeatmapSet(
    val _beatmapCharacteristicName: String,
    val _difficultyBeatmaps: List<DifficultyBeatmap>
) {
    fun validate(validator: Validator<DifficultyBeatmapSet>, files: Set<String>, getFile: (String) -> Path?, info: ExtractedInfo) = validator.apply {
        validate(DifficultyBeatmapSet::_beatmapCharacteristicName).isNotNull().isIn("Standard", "NoArrows", "OneSaber", "360Degree", "90Degree", "Lightshow", "Lawless")
        validate(DifficultyBeatmapSet::_difficultyBeatmaps).validateForEach {
            it.validate(this, self(), files, getFile, info)
        }
    }

    private fun self() = this

    fun enumValue() = searchEnum<ECharacteristic>(_beatmapCharacteristicName)
}

data class DifficultyBeatmap(
    val _difficulty: String,
    val _difficultyRank: Int,
    val _beatmapFilename: String,
    val _noteJumpMovementSpeed: Float,
    val _noteJumpStartBeatOffset: Float,
    val _customData: DifficultyBeatmapCustomData?,
    @JsonIgnore @get:JsonAnyGetter val additionalInformation: LinkedHashMap<String, Any> = linkedMapOf()
) {
    @JsonAnySetter
    fun ignored(name: String, value: Any) {
        additionalInformation[name] = value
    }

    private fun diffValid(parent: Validator<*>.Property<*>, path: Path?, characteristic: DifficultyBeatmapSet, difficulty: DifficultyBeatmap, info: ExtractedInfo) = path?.inputStream().use { stream ->
        val byteArrayOutputStream = ByteArrayOutputStream()
        stream?.copyTo(byteArrayOutputStream, sizeLimit = 50 * 1024 * 1024)
        val bytes = byteArrayOutputStream.toByteArray()

        info.md.write(bytes)
        val jsonElement = Json.parseToJsonElement(bytes.decodeToString())
        val diff = if (jsonElement.jsonObject.containsKey("version")) {
            Json.decodeFromJsonElement<BSDifficultyV3>(jsonElement)
        } else {
            Json.decodeFromJsonElement<BSDifficulty>(jsonElement)
        }

        info.diffs.getOrPut(characteristic) {
            mutableMapOf()
        }[difficulty] = diff

        parent.addConstraintViolations(
            when (diff) {
                is BSDifficulty -> Validator(diff).apply { this.validate(info) }
                is BSDifficultyV3 -> Validator(diff).apply { this.validateV3(info) }
            }.constraintViolations.map { constraint ->
                DefaultConstraintViolation(
                    property = "`${path?.fileName}`.${constraint.property}",
                    value = constraint.value,
                    constraint = constraint.constraint
                )
            }
        )
    }

    private fun self() = this

    fun validate(validator: Validator<DifficultyBeatmap>, characteristic: DifficultyBeatmapSet, files: Set<String>, getFile: (String) -> Path?, info: ExtractedInfo) = validator.apply {
        extraFieldsViolation(
            constraintViolations,
            additionalInformation.keys,
            arrayOf("_warnings", "_information", "_suggestions", "_requirements", "_difficultyLabel", "_envColorLeft", "_envColorRight", "_colorLeft", "_colorRight")
        )

        val allowedDiffNames = setOf("Easy", "Normal", "Hard", "Expert", "ExpertPlus")
        validate(DifficultyBeatmap::_difficulty).isNotNull()
            .validate(In(allowedDiffNames)) { it == null || allowedDiffNames.any { dn -> dn.equals(it, true) } }
            .validate(UniqueDiff(_difficulty)) {
                !characteristic._difficultyBeatmaps.any {
                    it != self() && it._difficulty == self()._difficulty
                }
            }
        validate(DifficultyBeatmap::_difficultyRank).isNotNull().isIn(1, 3, 5, 7, 9)
        validate(DifficultyBeatmap::_beatmapFilename).isNotNull().validate(InFiles) { it == null || files.contains(it.lowercase()) }
            .also {
                if (files.contains(_beatmapFilename.lowercase())) {
                    diffValid(it, getFile(_beatmapFilename), characteristic, self(), info)
                }
            }
    }

    fun enumValue() = EDifficulty.fromInt(_difficultyRank) ?: searchEnum(_difficulty)
}

fun extraFieldsViolation(
    constraintViolations: MutableSet<ConstraintViolation>,
    keys: Set<String>,
    notAllowed: Array<String> = arrayOf(
        "_warnings",
        "_information",
        "_suggestions",
        "_requirements",
        "_difficultyLabel",
        "_envColorLeft",
        "_envColorRight",
        "_colorLeft",
        "_colorRight"
    )
) {
    constraintViolations +=
        notAllowed.intersect(keys).map {
            DefaultConstraintViolation(
                property = it,
                value = null,
                constraint = MisplacedCustomData
            )
        }
}

fun Validator<BSDifficulty>.validate(info: ExtractedInfo) {
    validate(BSDifficulty::_version).isNotNull().matches(Regex("\\d+\\.\\d+\\.\\d+"))
    validate(BSDifficulty::_notes).isNotNull().validateForEach {
        validate(BSNote::_type).validate(NotNull) { it != Int.MIN_VALUE }.isIn(0, 1, 3)
        validate(BSNote::_cutDirection).validate(NotNull) { it != Int.MIN_VALUE }.validate(CutDirection) {
            it == null || (it in 0..8) || (it in 1000..1360)
        }
        validate(BSNote::_time).validate(NotNull) { it != Float.NEGATIVE_INFINITY }.let {
            if (info.duration > 0) {
                it.isBetween(0f, (info.duration / 60) * info.mapInfo._beatsPerMinute)
            }
        }
        validate(BSNote::_lineIndex).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSNote::_lineLayer).validate(NotNull) { it != Int.MIN_VALUE }
    }
    validate(BSDifficulty::_obstacles).isNotNull().validateForEach {
        validate(BSObstacle::_type).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSObstacle::_duration).validate(NotNull) { it != Long.MIN_VALUE }
        validate(BSObstacle::_time).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSObstacle::_lineIndex).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSObstacle::_width).validate(NotNull) { it != Int.MIN_VALUE }
    }
    validate(BSDifficulty::_events).isNotNull().validateForEach {
        validate(BSEvent::_time).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSEvent::_type).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSEvent::_value).validate(NotNull) { it != Int.MIN_VALUE }
    }
}

fun Validator<BSDifficultyV3>.validateV3(info: ExtractedInfo) {
    validate(BSDifficultyV3::version).isNotNull().matches(Regex("\\d+\\.\\d+\\.\\d+"))
    validate(BSDifficultyV3::bpmEvents).isNotNull().validateForEach {
        validate(BSBpmChange::bpm).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSBpmChange::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
    }
    validate(BSDifficultyV3::rotationEvents).isNotNull().validateForEach {
        validate(BSRotationEvent::executionTime).validate(NotNull) { it != Int.MIN_VALUE }.isIn(0, 1)
        validate(BSRotationEvent::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSRotationEvent::rotation).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
    }
    validate(BSDifficultyV3::colorNotes).isNotNull().validateForEach {
        validate(BSNoteV3::color).validate(NotNull) { it != Int.MIN_VALUE }.isIn(0, 1)
        validate(BSNoteV3::direction).validate(NotNull) { it != Int.MIN_VALUE }.validate(CutDirection) {
            it == null || (it in 0..8)
        }
        validate(BSNoteV3::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }.let {
            if (info.duration > 0) {
                it.isBetween(0f, (info.duration / 60) * info.mapInfo._beatsPerMinute)
            }
        }
        validate(BSNoteV3::x).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSNoteV3::y).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSNoteV3::angle).validate(NotNull) { it != Int.MIN_VALUE }
    }
    validate(BSDifficultyV3::bombNotes).isNotNull().validateForEach {
        validate(BSBomb::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSBomb::x).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSBomb::y).validate(NotNull) { it != Int.MIN_VALUE }
    }
    validate(BSDifficultyV3::obstacles).isNotNull().validateForEach {
        validate(BSObstacleV3::duration).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSObstacleV3::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSObstacleV3::x).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSObstacleV3::y).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSObstacleV3::width).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSObstacleV3::height).validate(NotNull) { it != Int.MIN_VALUE }
    }
    validate(BSDifficultyV3::sliders).isNotNull().validateForEach {
        validate(BSSlider::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSSlider::color).validate(NotNull) { it != Int.MIN_VALUE }.isIn(0, 1)
        validate(BSSlider::x).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSSlider::y).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSSlider::direction).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSSlider::tailBeat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSSlider::tailX).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSSlider::tailY).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSSlider::headControlPointLengthMultiplier).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSSlider::tailControlPointLengthMultiplier).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSSlider::tailCutDirection).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSSlider::sliderMidAnchorMode).validate(NotNull) { it != Int.MIN_VALUE }
    }
    validate(BSDifficultyV3::burstSliders).isNotNull().validateForEach {
        validate(BSBurstSlider::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }.let {
            if (info.duration > 0) {
                it.isBetween(0f, (info.duration / 60) * info.mapInfo._beatsPerMinute)
            }
        }
        validate(BSBurstSlider::color).validate(NotNull) { it != Int.MIN_VALUE }.isIn(0, 1)
        validate(BSBurstSlider::x).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSBurstSlider::y).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSBurstSlider::direction).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSBurstSlider::tailBeat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }.let {
            if (info.duration > 0) {
                it.isBetween(0f, (info.duration / 60) * info.mapInfo._beatsPerMinute)
            }
        }
        validate(BSBurstSlider::tailX).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSBurstSlider::tailY).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSBurstSlider::sliceCount).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSBurstSlider::squishAmount).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
    }
    validate(BSDifficultyV3::basicBeatmapEvents).isNotNull().validateForEach {
        validate(BSEventV3::beat).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
        validate(BSEventV3::eventType).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSEventV3::value).validate(NotNull) { it != Int.MIN_VALUE }
        validate(BSEventV3::floatValue).validate(NotNull) { it != Float.NEGATIVE_INFINITY }
    }
}

data class DifficultyBeatmapCustomData(
    val _difficultyLabel: String?,
    val _editorOffset: Int?,
    val _editorOldOffset: Int?,
    val _warnings: List<String>?,
    val _information: List<String>?,
    val _suggestions: List<String>?,
    val _requirements: List<String>?,
    @JsonIgnore @get:JsonAnyGetter val additionalInformation: LinkedHashMap<String, Any> = linkedMapOf()
) {
    @JsonAnySetter
    fun ignored(name: String, value: Any) {
        additionalInformation[name] = value
    }
}
