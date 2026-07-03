package br.senai.realsensemapper

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.senai.realsensemapper.domain.ScanInfo
import br.senai.realsensemapper.domain.ScanRepository
import br.senai.realsensemapper.domain.formatBytes
import java.io.File

class ScansActivity : AppCompatActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var adapter: ScanAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scans)
        repo = ScanRepository(File(getExternalFilesDir(null), "scans"))
        adapter = ScanAdapter(::shareScan, ::confirmDelete)
        findViewById<RecyclerView>(R.id.scanList).apply {
            layoutManager = LinearLayoutManager(this@ScansActivity)
            adapter = this@ScansActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.submit(repo.listScans())
    }

    private fun shareScan(scan: ScanInfo) {
        val uri = FileProvider.getUriForFile(
            this, "br.senai.realsensemapper.fileprovider", scan.file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.action_share)))
    }

    private fun confirmDelete(scan: ScanInfo) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.action_delete) + " ${scan.name}?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repo.delete(scan)
                adapter.submit(repo.listScans())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

class ScanAdapter(
    val onShare: (ScanInfo) -> Unit,
    val onDelete: (ScanInfo) -> Unit,
) : RecyclerView.Adapter<ScanAdapter.Holder>() {

    private var scans: List<ScanInfo> = emptyList()

    fun submit(items: List<ScanInfo>) {
        scans = items
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.scanName)
        val meta: TextView = view.findViewById(R.id.scanMeta)
        val share: ImageButton = view.findViewById(R.id.shareButton)
        val delete: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_scan, parent, false))

    override fun getItemCount() = scans.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val scan = scans[position]
        holder.name.text = scan.name
        val date = DateFormat.format("dd/MM/yyyy HH:mm", scan.modifiedAt)
        holder.meta.text = "$date — ${formatBytes(scan.sizeBytes)}"
        holder.share.setOnClickListener { onShare(scan) }
        holder.delete.setOnClickListener { onDelete(scan) }
    }
}
