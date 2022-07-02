import kotlinx.coroutines.runBlocking
import org.hildan.hashcode.utils.reader.HCReader
import org.hildan.hashcode.utils.solveHCFilesInParallel
import java.util.TreeMap

fun main(args: Array<String>) = runBlocking {
    solveHCFilesInParallel(*args) {
        readProblem().solve()
    }
}

data class Contributor(
    val name: String,
    val skills: List<Skill>,
    var dayAvailable: Int = 0,
) {
    private val skillsByName = skills.associateBy { it.name }

    fun getSkill(skillName: String) = skillsByName.getValue(skillName)
}

data class Skill(val name: String, var level: Int)

data class Project(
    val name: String,
    val duration: Int,
    val score: Int,
    val bestBefore: Int,
    val requiredSkills: List<SkillRequirement>,
)

data class SkillRequirement(
    val skillName: String,
    val minLevel: Int,
)

typealias SkillName = String

data class Problem(
    val contributors: List<Contributor>,
    val projects: List<Project>,
) {
    private val remainingProjects = projects.toMutableSet()

    private val skillsMap = buildMap<SkillName, TreeMap<Int, MutableList<Contributor>>> {
        contributors.forEach { c ->
            c.skills.forEach { s ->
                val levelsMap = getOrPut(s.name) { TreeMap() }
                val contribsOfSameSkill = levelsMap.getOrPut(s.level) { mutableListOf() }
                // TODO ORDER
                contribsOfSameSkill.add(c)
                contribsOfSameSkill.sortMostInterestingFirst()
            }
        }
    }

    fun solve(): List<String> {
        val assignments = mutableListOf<Assignment>()

        while (remainingProjects.isNotEmpty()) {
            val bestAssignment = remainingProjects
                .mapNotNull { findBestAssignmentFor(it) }
                .maxByOrNull { businessValue(it) } ?: break

            remainingProjects.remove(bestAssignment.project)
            assignments.add(bestAssignment)
            updateSkillsAndAvailability(bestAssignment)
        }
        return assignments.toSolutionLines()
    }

    // TODO find better heuristic
    private fun businessValue(it: Assignment) =
        it.effectiveScore.toDouble() / (it.project.duration * it.project.bestBefore)

    private fun findBestAssignmentFor(project: Project): Assignment? {
        val takenContribsNames = mutableSetOf<String>()
        val assignedDevsOrHoles = project.requiredSkills.mapTo(ArrayList()) { req ->
            findBestContributorFor(req, takenContribsNames)?.also { takenContribsNames.add(it.name) }
        }
        val nonReplacableDevIndices = fillHolesWithNoobs(assignedDevsOrHoles, project, takenContribsNames)

        val assignedDevs = assignedDevsOrHoles.allNotNullOrNull() ?: return null
        replaceWithNoobs(assignedDevs, project, takenContribsNames, nonReplacableDevIndices)

        val effectiveEndDate = assignedDevs.maxOf { it.dayAvailable } + project.duration
        val latenessPenalty = (effectiveEndDate - project.bestBefore).coerceAtLeast(0)
        val effectiveScore = (project.score - latenessPenalty).coerceAtLeast(0)
        return Assignment(
            project = project,
            contributors = assignedDevs,
            effectiveScore = effectiveScore,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutableList<Contributor?>.allNotNullOrNull() =
        if (any { it == null }) null else this as MutableList<Contributor>

    private fun fillHolesWithNoobs(
        assignedDevsOrHoles: MutableList<Contributor?>,
        project: Project,
        takenContribsNames: MutableSet<String>
    ): MutableSet<Int> {
        val nonReplacableDevIndices = mutableSetOf<Int>()
        val holeIndices = assignedDevsOrHoles.mapIndexedNotNullTo(HashSet()) { index, contributor ->
            if (contributor == null) index else null
        }
        while (holeIndices.isNotEmpty()) {
            val (holeIndex, duo) = holeIndices.firstNotNullOfOrNull { holeIndex ->
                val req = project.requiredSkills[holeIndex]
                val duo = findMentoreeFor(req, assignedDevsOrHoles, takenContribsNames)
                duo?.let { holeIndex to duo }
            } ?: break

            takenContribsNames.add(duo.noob.name)
            assignedDevsOrHoles[holeIndex] = duo.noob
            holeIndices.remove(holeIndex)
            nonReplacableDevIndices.add(holeIndex)
            nonReplacableDevIndices.add(duo.mentorIndex)
        }
        return nonReplacableDevIndices
    }

    private fun replaceWithNoobs(
        assignedDevs: MutableList<Contributor>,
        project: Project,
        takenContribsNames: MutableSet<String>,
        nonReplacableDevIndices: Set<Int>,
    ) {
        val replacableDevs = assignedDevs.indices.toMutableSet().apply {
            removeAll(nonReplacableDevIndices)
        }
        while (replacableDevs.isNotEmpty()) {
            val (replaceableDevIndex, duo) = replacableDevs.firstNotNullOfOrNull { replaceableDevIndex ->
                val req = project.requiredSkills[replaceableDevIndex]
                val duo = findMentoreeFor(req, assignedDevs, takenContribsNames)
                duo?.let { replaceableDevIndex to duo }
            } ?: break

            takenContribsNames.add(duo.noob.name)
            takenContribsNames.remove(assignedDevs[replaceableDevIndex].name)
            assignedDevs[replaceableDevIndex] = duo.noob
            replacableDevs.remove(replaceableDevIndex)
            replacableDevs.remove(duo.mentorIndex)
        }
    }

    private fun findBestContributorFor(skillRequirement: SkillRequirement, takenGuys: Set<String>): Contributor? {
        val contribsByLevel = skillsMap[skillRequirement.skillName] ?: return null
        var levelToFind = skillRequirement.minLevel
        while (true) {
            val entry = contribsByLevel.ceilingEntry(levelToFind) ?: return null
            val (actualLevel, validContribs) = entry
            val availableContributor = validContribs.firstOrNull { it.name !in takenGuys }
            if (availableContributor != null) {
                return availableContributor
            }
            levelToFind = actualLevel + 1
        }
    }

    data class Duo(val noob: Contributor, val mentorIndex: Int)

    private fun findMentoreeFor(
        req: SkillRequirement,
        takenGuys: List<Contributor?>,
        takenContribsNames: MutableSet<String>,
    ): Duo? {
        val contribsByLevel = skillsMap[req.skillName] ?: return null
        val potentialNoobs = contribsByLevel[req.minLevel - 1] ?: return null
        return potentialNoobs.firstNotNullOfOrNull { potentialNoob ->
            if (potentialNoob.name in takenContribsNames) {
                null
            } else {
                val mentorIndex = takenGuys.findIndexOfMentorFor(req)
                if (mentorIndex < 0) null else Duo(potentialNoob, mentorIndex)
            }
        }
    }

    private fun List<Contributor?>.findIndexOfMentorFor(req: SkillRequirement) = indexOfFirst { mentor ->
        mentor != null && mentor.skills.any { it.name == req.skillName && it.level >= req.minLevel }
    }

    private fun updateSkillsAndAvailability(assignment: Assignment) {
        val startDate = assignment.contributors.maxOf { it.dayAvailable }

        val listsToResort = mutableListOf<MutableList<Contributor>>()

        assignment.contributors.forEachIndexed { index, dev ->
            val req = assignment.project.requiredSkills[index]
            val skill = dev.getSkill(req.skillName)
            val devsByLevel = skillsMap.getValue(req.skillName)
            val devsOfSameLevel = devsByLevel.getValue(skill.level)

            if (skill.level <= req.minLevel) {
                devsOfSameLevel.remove(dev)
                if (devsOfSameLevel.isEmpty()) {
                    devsByLevel.remove(skill.level)
                }
                skill.level++

                val devsOfUpgradedLevel = devsByLevel.getOrPut(skill.level) { mutableListOf() }
                devsOfUpgradedLevel.add(dev)
                listsToResort.add(devsOfUpgradedLevel)
            } else {
                listsToResort.add(devsOfSameLevel)
            }

            // TODO think about using the availability before that project starts
            dev.dayAvailable = startDate + assignment.project.duration
        }

        listsToResort.forEach { it.sortMostInterestingFirst() }
    }
}

private fun MutableList<Contributor>.sortMostInterestingFirst() {
    sortBy { it.skills.size * it.dayAvailable }
}

data class Assignment(
    val project: Project,
    val contributors: List<Contributor>,
    val effectiveScore: Int,
)

private fun List<Assignment>.toSolutionLines(): List<String> {
    val assignments = this@toSolutionLines
    return buildList {
        add(assignments.size.toString())
        assignments.forEach {
            add(it.project.name)
            add(it.contributors.joinToString(" ") { it.name })
        }
    }
}

private fun HCReader.readProblem(): Problem {
    val nContribs = readInt()
    val nProjects = readInt()
    val contribs = List(nContribs) { readContributor() }
    val projects = List(nProjects) { readProject() }
    return Problem(contribs, projects)
}

private fun HCReader.readContributor() : Contributor {
    val name = readString()
    val nSkills = readInt()
    val skills = List(nSkills) { readSkill() }
    return Contributor(name, skills)
}

private fun HCReader.readSkill(): Skill {
    val name = readString()
    val level = readInt()
    return Skill(name, level)
}

private fun HCReader.readProject() : Project {
    val name = readString()
    val nDaysToComplete = readInt()
    val score = readInt()
    val bestBefore = readInt()
    val skills = List(readInt()) { readSkillRequirement() }
    return Project(name, nDaysToComplete, score, bestBefore, skills)
}

private fun HCReader.readSkillRequirement(): SkillRequirement {
    val name = readString()
    val level = readInt()
    return SkillRequirement(name, level)
}
