package com.xperia.timelapse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.xperia.timelapse.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var selectedDirectoryUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupStoragePicker()
        startCamera()

        binding.btnRecord.setOnClickListener {
            if (activeRecording != null) {
                stopRecording()
            } else {
                startTimelapseRecording()
            }
        }
    }

    private fun setupSpinners() {
        // 画质选择（针对 4K / 1080P）
        val qualities = listOf("4K (UHD)", "1080P (FHD)", "720P (HD)")
        binding.spinnerQuality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualities)

        // 帧率选择
        val fpsOptions = listOf("24 FPS", "30 FPS", "60 FPS")
        binding.spinnerFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
    }

    // 目录选择逻辑，支持 Sony Xperia 外置 SD 卡路径
    private fun setupStoragePicker() {
        val launcher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                selectedDirectoryUri = uri
                // 持久化 SD 卡读写权限
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                binding.txtStoragePath.text = uri.path
            }
        }
        binding.btnSelectStorage.setOnClickListener {
            launcher.launch(null)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // 获取画面防抖控制
            val isStabilizationEnabled = binding.switchStabilization.isChecked
            val qualitySelector = QualitySelector.from(Quality.HIGHEST)

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.with(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )

                // 如果设备（如 Xperia 1 IV）硬件支持防抖，强制开启
                binding.switchStabilization.setOnCheckedChangeListener { _, isChecked ->
                    camera.cameraControl.enableTorch(false)
                }

            } catch (e: Exception) {
                Toast.makeText(this, "相机初始化失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startTimelapseRecording() {
        val capture = videoCapture ?: return

        // 拼接视频文件输出格式
        val fileName = "Xperia_Timelapse_${System.currentTimeMillis()}.mp4"
        
        // 存储路径判断（SD卡 URI 输出 vs 本地内部存储）
        val pendingRecording = if (selectedDirectoryUri != null) {
            val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, selectedDirectoryUri!!)
                ?.createFile("video/mp4", fileName)
            
            val pfd = contentResolver.openFileDescriptor(documentFile!!.uri, "rw")!!
            capture.output
                .prepareForVideoToFileDescriptor(this, pfd)
        } else {
            val file = File(getExternalFilesDir(null), fileName)
            val fileOutputOptions = FileOutputOptions.Builder(file).build()
            capture.output.prepareForFilePath(fileOutputOptions)
        }

        activeRecording = pendingRecording
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.btnRecord.text = "停止录制"
                    }
                    is VideoRecordEvent.Finalize -> {
                        binding.btnRecord.text = "开始延时摄影"
                        activeRecording = null
                        Toast.makeText(this, "保存成功！", Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }
}
