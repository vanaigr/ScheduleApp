package com.idk.schedule

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.MenuCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.*


class MainActivity : AppCompatActivity() {
    private var group = false

    lateinit var schedule: Schedule

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sharedPref = getSharedPreferences("info", Context.MODE_PRIVATE)
        group = sharedPref.getBoolean("group", group)

        val scheduleString = try {
            readSchedule()
        }
        catch(e: Throwable) {
            val toast = Toast.makeText(applicationContext, "Error opening schedule file: $e",
                                       Toast.LENGTH_LONG
            )
            toast.show()
            "0,  0,0,0,0,0,0,0,  01,09,2022, 1,0,"
        }

        findViewById<Button>(R.id.accept).setOnClickListener {
            val source = findViewById<TextView>(R.id.getText).text.toString()
            val end = source.length

            val year_ = StringView(source, 0, end).parseNextValue()
            val month_ = StringView(source, year_.end + 1, end).parseNextValue()
            val day_ = StringView(source, month_.end + 1, end).parseNextValue()
            val hour_ = StringView(source, day_.end + 1, end).parseNextValue()
            val minute_ = StringView(source, hour_.end + 1, end).parseNextValue()

            val year = year_.get().trim().toInt()
            val month = month_.get().trim().toInt()
            val day = day_.get().trim().toInt()
            val hour = hour_.get().trim().toInt()
            val minute = minute_.get().trim().toInt()

            val c = Calendar.getInstance()
            c.clear()
            c.set(year, month - 1, day, hour, minute)

            updateScheduleDisplay(c)
        }

        with(findViewById<Switch>(R.id.groupSwitch)) {
            isChecked = group

            setOnCheckedChangeListener { _, isChecked ->
                group = isChecked
                val editor = sharedPref.edit()
                editor.putBoolean("group", group)
                editor.apply()
                updateScheduleDisplay(Calendar.getInstance())
            }
        }

        val settingsB = findViewById<View>(R.id.settings)
        val settingsPopup = PopupMenu(this, settingsB)
        settingsPopup.menuInflater.inflate(R.menu.parameters, settingsPopup.menu)
        settingsPopup.menu.add(1, Menu.FIRST, Menu.NONE, "Выбрать файл...")
        MenuCompat.setGroupDividerEnabled(settingsPopup.menu, true)
        settingsB.setOnClickListener { settingsPopup.show() }
        settingsPopup.setOnMenuItemClickListener { return@setOnMenuItemClickListener when(it.itemId) {
            Menu.FIRST -> {
                openFile(); true
            }
            else -> false
        } }

        schedule = parseSchedule(scheduleString)

        updateScheduleDisplayTimed()
    }

    private fun updateScheduleDisplayTimed() {
        Log.d("Update", "Updated!")

        val next = Calendar.getInstance()
        next.add(Calendar.MINUTE, 1)

        val nextDate = Date(
            next.get(Calendar.YEAR) - 1900,
            next.get(Calendar.MONTH),
            next.get(Calendar.DAY_OF_MONTH),
            next.get(Calendar.HOUR_OF_DAY),
            next.get(Calendar.MINUTE)
        )

        window.decorView.post{ updateScheduleDisplay(Calendar.getInstance()) }

        Timer().schedule(object : TimerTask() {
            override fun run() {
                updateScheduleDisplayTimed()
            }
        }, nextDate)
    }

    private fun updateScheduleDisplay(now: Calendar) {
        val elements = findViewById<ViewGroup>(R.id.elements).also { it.removeAllViews() }
        val inflater = LayoutInflater.from(this)

        val addEl = fun(id: Int): View {
            val el_l = inflater.inflate(R.layout.element_l, elements, false) as ViewGroup
            val container = el_l.findViewById<ViewGroup>(R.id.container)
            elements.addView(el_l)
            val el = inflater.inflate(id, container, false)
            container.addView(el)
            return el_l
        }
        fun View.setElementElevation(elevation: Float) {
            (this as CardView).cardElevation = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, elevation, resources.displayMetrics
            )
        }
        var scrollTo: View? = null
        var endOfDay = false

        val curHour = now.get(Calendar.HOUR_OF_DAY)
        val curMinute = now.get(Calendar.MINUTE)
        val curDayOfWeek = floorMod(now.get(Calendar.DAY_OF_WEEK) - 2, 7) //Monday is 2 but should be 0, Sunday is 0 but should be 6
        val curMinuteOfDay = curHour * 60 + curMinute

        //very robust
        val yearStart = run {
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.set(
                schedule.weeksDescription.year,
                schedule.weeksDescription.month - 1,
                schedule.weeksDescription.day
            )
            val weekOfYear = calendar.get(Calendar.WEEK_OF_YEAR)
            calendar.clear()
            calendar.set(Calendar.YEAR, schedule.weeksDescription.year)
            calendar.set(Calendar.WEEK_OF_YEAR, weekOfYear)

            Date(
                calendar.get(Calendar.YEAR) - 1900,
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).time
        }
        val curDay = Date(
            now.get(Calendar.YEAR) - 1900,
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).time
        val weekDiff = floorDiv(
            TimeUnit.DAYS.convert(curDay - yearStart, TimeUnit.MILLISECONDS),
            7L
        ).toInt()
        val weekIndex = schedule.weeksDescription.weeks[floorMod(
            weekDiff,
            schedule.weeksDescription.weeks.size
        )]
        val currentDay = schedule.week[curDayOfWeek]

        findViewById<TextView>(R.id.weekIndex).text = if(weekIndex) "Знаменатель" else "Числитель"
        val curEndTV = findViewById<TextView>(R.id.currentEnd)
        val nextEndTV = findViewById<TextView>(R.id.nextEnd)

        val currentLessonIndices = currentDay.getForGroupAndWeek(group, weekIndex)

        val lessonIndicesRange = run {
            var first = currentLessonIndices.size
            var last = -1
            for((i, lessonIndex) in currentLessonIndices.withIndex()) {
                if(lessonIndex != 0) {
                    first = min(first, i)
                    last = max(last, i)
                }
            }
            IntRange(first, last)
        }

        for(i in lessonIndicesRange) {
            val lessonIndex = currentLessonIndices[i]
            val lessonEl = addEl(R.layout.element)

            val lessonMinutes = currentDay.time[i]
            val nextLessonI = run {
                var nextI: Int = lessonIndicesRange.last+1
                for(nextI_ in i+1..lessonIndicesRange.last) {
                    if(currentLessonIndices[nextI_] != 0) {
                        nextI = nextI_
                        break
                    }
                }
                nextI
            }
            val nextLessonMinutes = if(nextLessonI in lessonIndicesRange) currentDay.time[nextLessonI] else null

            val lesson = if(lessonIndex == 0) {
                lessonEl.setElementTextEmpty(lessonMinutes)
                null
            }
            else {
                val lesson = currentDay.lessonsUsed[lessonIndex - 1]
                lessonEl.setElementText(lesson, lessonMinutes)
                lesson
            }

            when {
                lessonMinutes.last < curMinuteOfDay    -> {
                    lessonEl.setElementForeground(R.color.prev_el_overlay)
                    lessonEl.scaleX = 0.9f
                    lessonEl.scaleY = 0.9f
                    endOfDay = true
                }
                lessonMinutes.first > curMinuteOfDay -> {
                    lessonEl.setElementForeground(R.color.next_el_overlay)
                    lessonEl.scaleX = 0.9f
                    lessonEl.scaleY = 0.9f
                }
                else -> {
                    lessonEl.setElementForeground(android.R.color.transparent)

                    if(lesson == null) {
                        scrollTo = lessonEl
                        if(nextLessonMinutes != null) {
                            curEndTV.text = "До конца перемены: ${nextLessonMinutes.first - curMinuteOfDay} мин."
                            nextEndTV.text = "До конца след. пары: ${nextLessonMinutes.last - curMinuteOfDay} мин."
                        }
                    }
                    else {
                        scrollTo = lessonEl

                        curEndTV.text = "До конца пары: ${lessonMinutes.last - curMinuteOfDay} мин."
                        if(nextLessonMinutes != null) {
                            nextEndTV.text = "До конца перемены: ${nextLessonMinutes.first - curMinuteOfDay} мин."
                        }
                        else nextEndTV.text = "Последняя пара"
                    }

                    lessonEl.setElementElevation(7.5f)
                }
            }

            if(nextLessonMinutes != null) {
                val breakEl = addEl(R.layout.break_l)

                breakEl.setBreakText(
                    "${minuteOfDayToString(lessonMinutes.last)}-${
                        minuteOfDayToString(
                            nextLessonMinutes.first
                        )
                    }",
                    "Перемена ${nextLessonMinutes.first - lessonMinutes.last} мин."
                )

                breakEl.setElementElevation(1.0f)

                when {
                    nextLessonMinutes.first <= curMinuteOfDay -> {
                        breakEl.setElementForeground(R.color.prev_el_overlay)
                        breakEl.scaleX = 0.9f
                        breakEl.scaleY = 0.9f
                    }
                    lessonMinutes.last >= curMinuteOfDay -> {
                        breakEl.setElementForeground(R.color.next_el_overlay)
                        breakEl.scaleX = 0.9f
                        breakEl.scaleY = 0.9f
                    }
                    else -> {
                        breakEl.setElementForeground(android.R.color.transparent)
                        scrollTo = breakEl
                        curEndTV.text = "До конца перемены: ${nextLessonMinutes.first - curMinuteOfDay} мин."
                        nextEndTV.text = "До конца след. пары: ${nextLessonMinutes.last - curMinuteOfDay} мин."
                        breakEl.setElementElevation(7.5f)
                    }
                }
            }
        }

        run {
            val sv = scrollTo
            val scrollView = findViewById<ScrollView>(R.id.elementsSV)
            scrollView.post {
                if(sv != null) scrollView.scrollTo(0,
                                                   (sv.top + sv.bottom) / 2 - scrollView.height / 2
                )
                if(endOfDay) scrollView.fullScroll(scrollView.bottom)
                else scrollView.fullScroll(0)
            }

            if(sv == null) {
                when {
                    lessonIndicesRange.isEmpty() -> {
                        curEndTV.text = "Выходной"
                    }
                    endOfDay                     -> {
                        curEndTV.text = "Конец учебного дня"
                    }
                    else                         -> {
                        curEndTV.text = "До начала учебного дня: ${currentDay.time[lessonIndicesRange.first].first - curMinuteOfDay} мин."
                    }
                }
                nextEndTV.text = ""
            }
        }

        //Log.d("AA", "${scrollTo?.let { (it.top + it.bottom) } ?: "none"}")
    }

    private fun writeSchedule(text: String) {
        val file = openFileOutput("schedule_0.scd", Context.MODE_PRIVATE)
        file.use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun readSchedule(): String {
        val file = openFileInput("schedule_0.scd")
        return file.use { stream -> String(stream.readBytes(), Charsets.UTF_8) }
    }

    private fun View.setBreakText(time: String, text: String) {
        findViewById<TextView>(R.id.timeTV).text = time
        findViewById<TextView>(R.id.textTV).text = text
    }

    private fun View.setElementText(element: Lesson, time: IntRange) = setElementText(
        minuteOfDayToString(time.first) + "-" + minuteOfDayToString(time.last),
        element.type,
        element.loc,
        element.name,
        element.extra
    )

    private fun View.setElementText(time: String, type: String, loc: String, name: String,
                                    extra: String
    ) {
        findViewById<TextView>(R.id.timeTV).text = time
        findViewById<TextView>(R.id.typeTV).text = type
        findViewById<TextView>(R.id.locTV).text = loc
        findViewById<TextView>(R.id.nameTV).text = name
        findViewById<TextView>(R.id.extraTV).text = extra
    }

    private fun View.setElementTextEmpty(time: IntRange) = setElementText(
        minuteOfDayToString(time.first) + "-" + minuteOfDayToString(time.last),
        "",
        "",
        "Окно",
        ""
    )

    private fun View.setElementForeground(foreground: Int) {
        findViewById<View>(R.id.foreground).setBackgroundResource(foreground)
    }

    //very nice API 24 Math. functions
    private fun floorDiv(x: Int, y: Int): Int {
        var r = x / y
        // if the signs are different and modulo not zero, round down
        if(x xor y < 0 && r * y != x) {
            r--
        }
        return r
    }

    private fun floorMod(x: Int, y: Int): Int {
        return x - floorDiv(x, y) * y
    }

    private fun floorDiv(x: Long, y: Long): Long {
        var r = x / y
        // if the signs are different and modulo not zero, round down
        if(x xor y < 0 && r * y != x) {
            r--
        }
        return r
    }

    private fun floorMod(x: Long, y: Long): Long {
        return x - floorDiv(x, y) * y
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/*"
        }
        startActivityForResult(intent, 1)
    }

    @SuppressLint("MissingSuperCall")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            resultData?.data?.also { uri ->
                val text = readTextFromUri(uri)
                try {
                    schedule = parseSchedule(text)
                    writeSchedule(text)
                    updateScheduleDisplay(Calendar.getInstance())
                } catch(e: Exception) {
                    val toast = Toast.makeText(
                        applicationContext,
                        "Error parsing schedule from file: $e",
                        Toast.LENGTH_LONG
                    )
                    toast.show()
                }
            }
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val stringBuilder = StringBuilder()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line)
                    line = reader.readLine()
                }
            }
        }
        return stringBuilder.toString()
    }
}