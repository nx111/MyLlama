package com.nx111.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class InstalledModelAdapter(
    private val onClick: (InstalledModel) -> Unit,
    private val onLongClick: (InstalledModel) -> Unit
) : RecyclerView.Adapter<InstalledModelAdapter.ModelViewHolder>() {
    private val models = mutableListOf<InstalledModel>()

    fun submitList(nextModels: List<InstalledModel>) {
        models.clear()
        models.addAll(nextModels)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_huggingface_model, parent, false)
        return ModelViewHolder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(models[position])
    }

    override fun getItemCount() = models.size

    class ModelViewHolder(
        view: View,
        private val onClick: (InstalledModel) -> Unit,
        private val onLongClick: (InstalledModel) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val titleTv: TextView = view.findViewById(R.id.hf_model_title)
        private val fileTv: TextView = view.findViewById(R.id.hf_model_file)
        private val metaTv: TextView = view.findViewById(R.id.hf_model_meta)

        fun bind(model: InstalledModel) {
            titleTv.text = model.name
            fileTv.text = model.file.name
            metaTv.text = "${model.sizeLabel}  ${model.file.parent.orEmpty()}"
            itemView.setOnClickListener { onClick(model) }
            itemView.setOnLongClickListener {
                onLongClick(model)
                true
            }
        }
    }
}
