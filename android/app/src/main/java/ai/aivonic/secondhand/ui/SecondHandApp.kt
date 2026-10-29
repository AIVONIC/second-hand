package ai.aivonic.secondhand.ui

import ai.aivonic.secondhand.Answer
import ai.aivonic.secondhand.Glasses
import ai.aivonic.secondhand.JobModel
import ai.aivonic.secondhand.JobState
import ai.aivonic.secondhand.MainActivity
import ai.aivonic.secondhand.Report
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Amber = Color(0xFFF2A900)
private val Ink = Color(0xFF121212)
private val Panel = Color(0xFF1E1E1E)
private val Muted = Color(0xFF9E9E9E)
private val StopRed = Color(0xFFD32F2F)
private val Ok = Color(0xFF43A047)

@Composable
fun SecondHandApp(
    job: JobModel,
    activity: MainActivity,
    onConnectGlasses: () -> Unit,
    onStartJob: (useGlasses: Boolean) -> Unit,
) {
  val state by job.state.collectAsState()
  val registered by activity.registered.collectAsState()
  val registering by activity.registering.collectAsState()
  var tab by remember { mutableIntStateOf(0) }
  var settings by remember { mutableStateOf(job.token.isBlank()) }

  MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Ink, background = Ink, surface = Panel)) {
    Surface(Modifier.fillMaxSize(), color = Ink) {
      Column(Modifier.systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("Second Hand", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber)
          Spacer(Modifier.weight(1f))
          TextButton(onClick = { settings = !settings }) { Text(if (settings) "Close" else "Settings", color = Muted) }
        }
        if (settings) {
          Settings(job) { settings = false }
          return@Column
        }
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Ink, contentColor = Amber) {
          Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Live") })
          Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Job (${state.steps.size})") })
          Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Report") })
        }
        // "job done": the report screen comes up by itself while the report is written
        LaunchedEffect(state.reportWriting, state.report?.id) { if (state.reportWriting || state.report != null) tab = 2 }
        when (tab) {
          0 -> Live(job, state, registered, registering, onConnectGlasses, onStartJob)
          1 -> JobLog(state)
          else -> ReportScreen(job, state)
        }
      }
    }
  }
}

@Composable
private fun Settings(job: JobModel, onDone: () -> Unit) {
  var server by remember { mutableStateOf(job.server) }
  var token by remember { mutableStateOf(job.token) }
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Server", color = Muted)
    OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), singleLine = true)
    Text("Device token (from the person who runs the server; it stays on this phone)", color = Muted)
    OutlinedTextField(
        token,
        { token = it },
        Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    Button(
        onClick = {
          job.server = server
          job.token = token
          onDone()
        },
        enabled = token.isNotBlank() && server.isNotBlank(),
    ) {
      Text("Save")
    }
  }
}

@Composable
private fun Live(
    job: JobModel,
    state: JobState,
    registered: Boolean,
    registering: Boolean,
    onConnectGlasses: () -> Unit,
    onStartJob: (Boolean) -> Unit,
) {
  val running = state.connected || state.connecting
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    StatusRow(state, registered)
    state.problem?.let { Text(it, color = StopRed, modifier = Modifier.padding(top = 6.dp)) }
    state.glassesProblem?.let { Text("Glasses: $it", color = Muted, fontSize = 13.sp) }
    Spacer(Modifier.height(12.dp))

    if (!running) {
      if (!registered) {
        Button(onClick = onConnectGlasses, enabled = !registering, modifier = Modifier.fillMaxWidth()) {
          Text(if (registering) "Waiting for the Meta AI app..." else "Connect glasses")
        }
        Spacer(Modifier.height(8.dp))
      }
      Button(onClick = { onStartJob(true) }, enabled = registered, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("Start job", fontSize = 18.sp)
      }
      OutlinedButton(onClick = { onStartJob(false) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Start without glasses (phone microphone)")
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { job.snap() }, enabled = state.glasses == Glasses.Status.READY) { Text("Photo") }
        OutlinedButton(onClick = { job.finish() }, enabled = state.connected && !state.reportWriting && state.answers.isNotEmpty()) {
          Text("Finish + report")
        }
        OutlinedButton(onClick = { job.endJob() }, enabled = !state.reportWriting) { Text("End job") }
      }
    }

    val last = state.answers.lastOrNull()
    if (last != null && last.stop.isNotBlank()) StopBanner(last.stop)

    state.photo?.let { photo ->
      Spacer(Modifier.height(12.dp))
      Image(
          photo.asImageBitmap(),
          contentDescription = state.outlined ?: "photo from the glasses",
          modifier = Modifier.fillMaxWidth(),
          contentScale = ContentScale.FillWidth,
      )
    }

    Spacer(Modifier.height(12.dp))
    when {
      state.speaking -> Text("Speaking...", color = Amber, fontSize = 13.sp)
      state.thinking -> Text("Thinking...", color = Amber, fontSize = 13.sp)
      running && state.mic != "none" -> Text("Listening", color = Muted, fontSize = 13.sp)
    }
    if (state.hearing.isNotBlank()) {
      Text("“${state.hearing}”", color = Muted, fontStyle = FontStyle.Italic, fontSize = 16.sp)
    }
    last?.let { AnswerCard(it) }
    if (state.done) Text("Job done.", color = Ok, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

    if (state.connected) {
      Spacer(Modifier.height(16.dp))
      AskBox(job)
    }
  }
}

@Composable
private fun StatusRow(state: JobState, registered: Boolean) {
  Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
    Dot("Server", if (state.connected) Ok else if (state.connecting) Amber else Muted)
    val g =
        when (state.glasses) {
          Glasses.Status.READY -> Ok
          Glasses.Status.CONNECTING -> Amber
          Glasses.Status.STOPPED -> StopRed
          Glasses.Status.OFF -> if (registered) Muted else Color.DarkGray
        }
    Dot("Glasses", g)
    Text("Mic: ${state.mic}", color = Muted, fontSize = 12.sp)
  }
}

@Composable
private fun Dot(label: String, color: Color) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
    Spacer(Modifier.width(5.dp))
    Text(label, fontSize = 13.sp)
  }
}

@Composable
private fun StopBanner(reason: String) {
  Card(
      colors = CardDefaults.cardColors(containerColor = StopRed),
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
  ) {
    Column(Modifier.padding(14.dp)) {
      Text("STOP", fontWeight = FontWeight.Black, fontSize = 22.sp, color = Color.White)
      Text(reason, color = Color.White)
    }
  }
}

@Composable
private fun AnswerCard(a: Answer) {
  Card(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(12.dp)) {
    Column(Modifier.padding(14.dp)) {
      Text(a.say, fontSize = 19.sp)
      if (a.displayOriginal.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text("On the display", color = Muted, fontSize = 12.sp)
        Text(a.displayOriginal, fontWeight = FontWeight.Bold)
        Text("= ${a.displayEnglish}", color = Amber)
      }
      Text("%.1f s".format(a.thinkSeconds), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
  }
}

@Composable
private fun AskBox(job: JobModel) {
  var text by remember { mutableStateOf("") }
  val send = {
    job.ask(text)
    text = ""
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(
        text,
        { text = it },
        Modifier.weight(1f),
        placeholder = { Text("Or type a question") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
    )
    Spacer(Modifier.width(8.dp))
    Button(onClick = send, enabled = text.isNotBlank(), colors = ButtonDefaults.buttonColors()) { Text("Ask") }
  }
}

@Composable
private fun JobLog(state: JobState) {
  LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (state.steps.isEmpty() && state.stops.isEmpty()) {
      item { Text("Nothing logged yet. Steps appear here as the job goes.", color = Muted) }
    }
    if (state.steps.isNotEmpty()) item { Text("Steps", color = Amber, fontWeight = FontWeight.Bold) }
    itemsIndexed(state.steps) { i, step -> Text("${i + 1}. $step") }
    if (state.stops.isNotEmpty()) {
      item { Text("Stopped for a professional", color = StopRed, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
      items(state.stops) { Text("• $it") }
    }
    if (state.answers.isNotEmpty()) {
      item { Text("Conversation", color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
      items(state.answers) { a ->
        Column {
          if (a.heard.isNotBlank()) Text("“${a.heard}”", color = Muted, fontStyle = FontStyle.Italic)
          Text(a.say)
        }
      }
    }
  }
}

@Composable
private fun ReportScreen(job: JobModel, state: JobState) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val r = state.report
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    state.reportProblem?.let { Text(it, color = StopRed, modifier = Modifier.padding(bottom = 8.dp)) }
    when {
      state.reportWriting ->
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 24.dp)) {
            CircularProgressIndicator(color = Amber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Text("Writing the job report and invoice draft with Muse Spark Open...", color = Muted)
          }
      r == null -> Text("Say \"job done\" (or press Finish + report) and the report appears here.", color = Muted)
      else -> {
        Text(r.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(listOf(r.site, r.date, "${r.minutes} min on site").filter { it.isNotBlank() }.joinToString("  ·  "), color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Text(r.summary, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 14.dp)) {
          Button(onClick = { scope.launch { job.reportPdf()?.let { share(context, it) } } }) { Text("Send PDF") }
          OutlinedButton(onClick = { scope.launch { job.reportPdf()?.let { open(context, it) } } }) { Text("Open PDF") }
        }
        r.referrals.forEach { (issue, who) -> StopBanner("$issue\nCall: $who") }
        Section("Findings", r.findings)
        Section("Work done", r.workDone)
        Section("Follow-up", r.followUp)
        Section("Parts used", r.parts)
        InvoiceCard(r.invoice)
        Text("Written by ${r.model} in %.1f s".format(r.seconds), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
      }
    }
  }
}

@Composable
private fun Section(title: String, items: List<String>) {
  if (items.isEmpty()) return
  Text(title, color = Amber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
  items.forEach { Text("•  $it", modifier = Modifier.padding(bottom = 3.dp)) }
}

@Composable
private fun InvoiceCard(inv: Report.Invoice) {
  fun money(v: Double?) = v?.let { "%,.2f".format(it).replace(',', ' ') } ?: "to add"
  Card(Modifier.fillMaxWidth().padding(top = 18.dp), shape = RoundedCornerShape(12.dp)) {
    Column(Modifier.padding(14.dp)) {
      Text("Invoice draft", color = Amber, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(6.dp))
      inv.lines.forEach { l ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
          Text("${l.text} (${l.quantity})", Modifier.weight(1f), fontSize = 14.sp)
          Text(money(l.amount), fontSize = 14.sp)
        }
      }
      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth()) { Text("VAT ${"%.0f".format(inv.vatRate * 100)}%", Modifier.weight(1f), color = Muted); Text(money(inv.vat), color = Muted) }
      Row(Modifier.fillMaxWidth()) {
        Text("Total", Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text("${money(inv.total)} ${inv.currency}", fontWeight = FontWeight.Bold)
      }
      if (inv.partsUnpriced) Text("Part prices to be added by the office.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
  }
}

private fun uri(context: android.content.Context, file: java.io.File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/** The share sheet: WhatsApp, email, anything that takes a PDF. */
private fun share(context: android.content.Context, file: java.io.File) {
  val send =
      Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri(context, file))
        putExtra(Intent.EXTRA_SUBJECT, "Job report")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
  context.startActivity(Intent.createChooser(send, "Send the job report"))
}

private fun open(context: android.content.Context, file: java.io.File) {
  val view =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri(context, file), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
  runCatching { context.startActivity(view) }
}
