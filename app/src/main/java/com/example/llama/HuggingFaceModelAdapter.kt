package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HuggingFaceModelAdapter(
    private val onClick: (HuggingFaceModel) -> Unit
) : RecyclerView.Adapter<HuggingFaceModelAdapter.ModelViewHolder>() {
    private val models = mutableListOf<HuggingFaceModel>()

    fun submitList(nextModels: List<HuggingFaceModel>) {
        models.clear()
        models.addAll(nextModels)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_huggingface_model, parent, false)
        return ModelViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(models[position])
    }

    override fun getItemCount() = models.size

    class ModelViewHolder(
        view: View,
        private val onClick: (HuggingFaceModel) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val titleTv: TextView = view.findViewById(R.id.hf_model_title)
        private val fileTv: TextView = view.findViewById(R.id.hf_model_file)
        private val metaTv: TextView = view.findViewById(R.id.hf_model_meta)

        fun bind(model: HuggingFaceModel) {
            titleTv.text = model.repoId
            fileTv.text = "点击选择量化版本 · ${model.files.size} 个版本 · 推荐 ${model.recommendedFile.quantization} · ${model.recommendedFile.sizeLabel}"
            metaTv.text = listOfNotNull(
                "下载 ${model.downloads.compact()}",
                "喜欢 ${model.likes.compact()}",
                model.trendingScore.takeIf { it > 0 }?.let { "热度 $it" },
                model.lastModified.takeIf { it.isNotBlank() }?.take(10),
                model.pipelineTag.takeIf { it.isNotBlank() },
                if (model.gated) "需要令牌" else null
            ).joinToString("  ")
            itemView.setOnClickListener { onClick(model) }
        }

        private fun Long.compact(): String = when {
            this >= 1_000_000 -> "${this / 1_000_000}M"
            this >= 1_000 -> "${this / 1_000}K"
            else -> toString()
        }
    }
}
