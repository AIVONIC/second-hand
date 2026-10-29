package ai.aivonic.secondhand

import org.json.JSONArray
import org.json.JSONObject

/** The job report and invoice draft, as the server sends them when the job is done (see server/secondhand/report.py). */
data class Report(
    val id: String,
    val title: String,
    val site: String,
    val date: String,
    val minutes: Int,
    val model: String,
    val seconds: Double,
    val summary: String,
    val findings: List<String>,
    val workDone: List<String>,
    val referrals: List<Pair<String, String>>, // issue, who to call
    val followUp: List<String>,
    val parts: List<String>,
    val invoice: Invoice,
) {
  data class Line(val text: String, val quantity: String, val unitPrice: Double?, val amount: Double?)

  data class Invoice(
      val currency: String,
      val lines: List<Line>,
      val subtotal: Double,
      val vatRate: Double,
      val vat: Double,
      val total: Double,
      val partsUnpriced: Boolean,
  )

  companion object {
    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

    private fun objects(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }

    private fun JSONObject.price(name: String): Double? = if (isNull(name)) null else getDouble(name)

    fun parse(m: JSONObject): Report {
      val inv = m.getJSONObject("invoice")
      return Report(
          id = m.getString("id"),
          title = m.getString("title"),
          site = m.optString("site"),
          date = m.optString("date"),
          minutes = m.optInt("minutes"),
          model = m.optString("model"),
          seconds = m.optDouble("seconds", 0.0),
          summary = m.getString("summary"),
          findings = strings(m.optJSONArray("findings")),
          workDone = strings(m.optJSONArray("work_done")),
          referrals = objects(m.optJSONArray("referrals")).map { it.getString("issue") to it.getString("who") },
          followUp = strings(m.optJSONArray("follow_up")),
          parts = objects(m.optJSONArray("parts")).map { "${it.getString("name")} (${it.getString("quantity")})" },
          invoice =
              Invoice(
                  currency = inv.getString("currency"),
                  lines =
                      objects(inv.getJSONArray("lines")).map {
                        Line(it.getString("text"), it.getString("quantity"), it.price("unit_price"), it.price("amount"))
                      },
                  subtotal = inv.getDouble("subtotal"),
                  vatRate = inv.getDouble("vat_rate"),
                  vat = inv.getDouble("vat"),
                  total = inv.getDouble("total"),
                  partsUnpriced = inv.optBoolean("parts_unpriced"),
              ),
      )
    }
  }
}
