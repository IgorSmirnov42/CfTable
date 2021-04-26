package com.codeforces

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.doyaaaaaken.kotlincsv.client.KotlinCsvExperimental
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.google.api.services.sheets.v4.Sheets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.json.JsonFactory
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.sheets.v4.model.ValueRange
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.lang.StringBuilder
import java.util.*
import java.util.stream.Collectors


data class Sheets(private val spreadsheetId: String) {

    companion object {
        private const val APPLICATION_NAME = "CodeforcesGetData"
        private val JSON_FACTORY: JsonFactory = JacksonFactory.getDefaultInstance()
        private const val TOKENS_DIRECTORY_PATH = "tokens"

        private val SCOPES: List<String> = Collections.singletonList(SheetsScopes.SPREADSHEETS_READONLY)
        private const val CREDENTIALS_FILE_PATH = "/credentials.json"
    }

    @Throws(IOException::class)
    private fun getCredentials(HTTP_TRANSPORT: NetHttpTransport): Credential {
        val credentials = Sheets::class.java.getResourceAsStream(CREDENTIALS_FILE_PATH)
            ?: throw FileNotFoundException("Resource not found: $CREDENTIALS_FILE_PATH")
        val clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, InputStreamReader(credentials))

        val flow = GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
            .setAccessType("offline")
            .build()
        val receiver = LocalServerReceiver.Builder().setPort(8888).build()
        return AuthorizationCodeInstalledApp(flow, receiver).authorize("user")
    }

    fun getAllUsers(range: String, getUsedData: (List<Any>) -> UserData?): List<UserData> {
        val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
        val service = Sheets.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
            .setApplicationName(APPLICATION_NAME)
            .build()
        val response: ValueRange = service.spreadsheets().values()[spreadsheetId, range]
            .execute()
        val values = response.getValues()
        if (values == null || values.isEmpty()) {
            throw RuntimeException("No data found.")

        } else {
            val result = mutableListOf<UserData>()
            for (row in values) {
                val userData = getUsedData(row)
                if (userData != null) {
                    result.add(userData)
                }
            }
            return result
        }
    }
}

data class UserData(
    val login: String,
    val name: String,
    val school: String,
)

val NullUser = UserData(
    login = "Нет анкеты",
    name = "Нет анкеты",
    school = "Нет анкеты",
)

@Suppress("UNCHECKED_CAST")
data class Codeforces(private val key: String, private val secret: String, private val contestId: String) {
    private val codeforcesHost = "https://codeforces.com/api/"
    private val client = OkHttpClient()

    private fun genRand(): String {
        var str = ""
        for (i in 1..6) {
            str += kotlin.random.Random.nextInt(0, 9)
        }
        return str
    }

    private fun hash(rand: String, url: String): String {
        val newUrl = "$rand/$url#$secret"
        return DigestUtils.sha512Hex(newUrl)
    }

    private fun sendRequest(@Suppress("SameParameterValue") handle: String, params: Map<String, String>): String {
        val time = System.currentTimeMillis() / 1000
        val paramsStr = (params.toList() + listOf("apiKey" to key, "time" to time.toString()))
            .sortedBy { it.first }
            .joinToString(separator = "&") { "${it.first}=${it.second}" }
        val url = "$handle?$paramsStr"
        val rand = genRand()
        val request = Request.Builder()
            .url("$codeforcesHost$url&apiSig=$rand${hash(rand, url)}")
            .get()
            .build()
        val response = client.newCall(request).execute()
        return response.body!!.string()
    }

    fun getContestProblems(): List<String> {
        val params = mapOf(
            "contestId" to contestId,
            "showUnofficial" to "true",
        )
        val body = sendRequest("contest.standings", params)
        val obj = Parser.default().parse(StringBuilder(body)) as JsonObject
        val problems = (obj["result"] as JsonObject)["problems"] as JsonArray<JsonObject>
        return problems.map { it["index"] as String }
    }

    fun getContestResults(): List<Contestant> {
        val params = mapOf(
            "contestId" to contestId,
            "showUnofficial" to "true",
        )
        val body = sendRequest("contest.standings", params)
        val obj = Parser.default().parse(StringBuilder(body)) as JsonObject
        val result = obj["result"] as JsonObject
        val rows = result["rows"] as JsonArray<JsonObject>
        val results = mutableListOf<Contestant>()
        for (row in rows) {
            val party = row["party"] as JsonObject
            val members = party["members"] as JsonArray<JsonObject>
            val handle = members[0]["handle"] as String
            val rank = row["rank"] as Int
            val points = row["points"] as Double
            val problems = (row["problemResults"] as JsonArray<JsonObject>).map { it["points"] as Double }
            results.add(
                Contestant(
                    login = handle,
                    rank = rank,
                    points = points,
                    problems = problems
                )
            )
        }
        return results
    }
}

data class Contestant(
    val login: String,
    val rank: Int,
    val points: Double,
    val problems: List<Double>
)

fun mapContestantToUser(contestant: Contestant, users: List<UserData>): UserData? {
    val possibleUsers = users.filter { it.login.trim().equals(contestant.login, ignoreCase = true) }
    if (possibleUsers.size == 1) {
        return possibleUsers[0]
    }
    println("Cannot map $contestant. Possibilities: $possibleUsers")
    return null
}

@KotlinCsvExperimental
fun printToCsv(
    pairs: List<Pair<Contestant, UserData?>>,
    csvName: String,
    printer: (Pair<Contestant, UserData?>) -> List<String>,
    headers: List<String>
) {
    val writer = csvWriter().openAndGetRawWriter("results/$csvName.csv")
    writer.writeRow(headers)
    pairs.forEach { writer.writeRow(printer(it)) }
    writer.close()
}

@KotlinCsvExperimental
fun printAllInformation(csvName: String, pairs: List<Pair<Contestant, UserData?>>, problemsList: List<String>) {
    printToCsv(
        pairs,
        csvName,
        { (contestant, userNullable) ->
            val user = userNullable ?: NullUser
            listOf(
                contestant.rank.toString(),
                user.name,
                contestant.points.toString(),
                contestant.login,
            ) + contestant.problems.map { it.toString() } + listOf(user.school)
        },
        listOf(
            "Место",
            "ФИО",
            "Баллы",
            "Логин на codeforces"
        ) + problemsList + listOf("Школа")
    )
}

class Table : CliktCommand() {
    private val contest: String by argument("contestId")
    private val spreadsheetId: String by option("--sheet").default("1pT_vTQKVkecRUOKIWsJyJyOB9H3FVLH07j51BN1871o")
    private val range: String by option("--range").default("A2:H")
    private val tableName: String by option("--table-name").default("results")

    @KotlinCsvExperimental
    override fun run() {
        val json = Parser.default().parse(Table::class.java.getResource("/secrets.json")!!.file) as JsonObject
        val key = json["key"] as String
        val secret = json["secret"] as String
        val codeforces = Codeforces(key, secret, contest)
        val contestants = codeforces.getContestResults()
        val users = Sheets(spreadsheetId).getAllUsers(range) { list ->
            if (list.size < 4) {
                null
            } else {
                UserData(
                    login = list[3] as String,
                    name = list[0] as String,
                    school = list[1] as String
                )
            }
        }
        val notContestants = setOf("Bykov_David")
        val contestantsWithUsers = contestants
            .filter { it.login !in notContestants }
            .filter { it.points > 0.5 }
            .map { it to mapContestantToUser(it, users) }
            .sortedBy { it.first.rank }
            .stream()
            .map(object : java.util.function.Function<Pair<Contestant, UserData?>, Pair<Contestant, UserData?>> {
                var better = 0
                var prev = 0
                var prevCnt = 0

                override fun apply(t: Pair<Contestant, UserData?>): Pair<Contestant, UserData?> {
                    if (t.first.rank != prev) {
                        better += prevCnt
                        prevCnt = 1
                        prev = t.first.rank
                        return t.first.copy(rank = better + 1) to t.second
                    } else {
                        prevCnt++
                    }
                    return t.first.copy(rank = better + 1) to t.second
                }
            })
            .collect(Collectors.toList())
        printAllInformation(tableName, contestantsWithUsers, codeforces.getContestProblems())
    }
}

fun main(args: Array<String>) {
    Table().main(args)
}