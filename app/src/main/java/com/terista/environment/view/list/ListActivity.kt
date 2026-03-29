package com.terista.environment.view.list

import java.io.File
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import cbfg.rvadapter.RVAdapter
import com.ferfalk.simplesearchview.SimpleSearchView
import com.terista.environment.R
import com.terista.environment.bean.InstalledAppBean
import com.terista.environment.databinding.ActivityListBinding
import com.terista.environment.util.InjectionUtil
import com.terista.environment.util.inflate
import com.terista.environment.view.base.BaseActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ListActivity : BaseActivity() {

    private val viewBinding: ActivityListBinding by inflate()
    private lateinit var mAdapter: RVAdapter<InstalledAppBean>
    private lateinit var viewModel: ListViewModel
    private var appList: List<InstalledAppBean> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        // 🔥 SYSTEM INSETS FIX (TOP + BOTTOM)
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.root) { _, insets ->

            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // ✅ Toolbar top padding
            viewBinding.toolbarLayout.toolbar.setPadding(
                viewBinding.toolbarLayout.toolbar.paddingLeft,
                systemBars.top,
                viewBinding.toolbarLayout.toolbar.paddingRight,
                viewBinding.toolbarLayout.toolbar.paddingBottom
            )

            // ✅ RecyclerView bottom padding
            viewBinding.recyclerView.setPadding(
                viewBinding.recyclerView.paddingLeft,
                viewBinding.recyclerView.paddingTop,
                viewBinding.recyclerView.paddingRight,
                systemBars.bottom
            )

            insets
        }

        initToolbar(viewBinding.toolbarLayout.toolbar, R.string.installed_app, true)

        mAdapter = RVAdapter<InstalledAppBean>(this, ListAdapter())
            .bind(viewBinding.recyclerView)
            .setItemClickListener { _, item, _ -> finishWithResult(item.sourceDir) }

        viewBinding.recyclerView.layoutManager = LinearLayoutManager(this)

        // Handle back press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewBinding.searchView.isSearchOpen) {
                    viewBinding.searchView.closeSearch()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        initSearchView()
        initViewModel()
    }

    private fun initSearchView() {
        viewBinding.searchView.setOnQueryTextListener(
            object : SimpleSearchView.OnQueryTextListener {
                override fun onQueryTextChange(newText: String): Boolean {
                    filterApp(newText)
                    return true
                }
                override fun onQueryTextCleared(): Boolean = true
                override fun onQueryTextSubmit(query: String): Boolean = true
            }
        )
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this, InjectionUtil.getListFactory())
            .get(ListViewModel::class.java)

        val userID = intent.getIntExtra("userID", 0)
        viewModel.getInstallAppList(userID)

        viewBinding.toolbarLayout.toolbar.setTitle(R.string.installed_app)

        viewModel.loadingLiveData.observe(this) {
            if (it) viewBinding.stateView.showLoading()
            else viewBinding.stateView.showContent()
        }

        viewModel.appsLiveData.observe(this) {
            if (it != null) {
                this.appList = it
                viewBinding.searchView.setQuery("", false)
                filterApp("")
                if (it.isNotEmpty()) {
                    viewBinding.stateView.showContent()
                    viewModel.previewInstalledList()
                } else {
                    viewBinding.stateView.showEmpty()
                }
            }
        }
    }

    private fun filterApp(newText: String) {
        val newList = this.appList.filter {
            it.name.contains(newText, true) || it.packageName.contains(newText, true)
        }
        mAdapter.setItems(newList)
    }

    private val openDocumentedResult =
    registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val inputStream = contentResolver.openInputStream(it)
            val tempFile = File(cacheDir, "install_temp.apk")

            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            finishWithResult(tempFile.absolutePath)
        }
    }

    private fun finishWithResult(source: String) {
        intent.putExtra("source", source)
        setResult(Activity.RESULT_OK, intent)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run { imm.hideSoftInputFromWindow(windowToken, 0) }

        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.list_choose) {
            openDocumentedResult.launch("application/vnd.android.package-archive")
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_list, menu)

        menu?.let {
            val item = it.findItem(R.id.list_search)
            viewBinding.searchView.setMenuItem(item)
        }

        return true
    }

    override fun onStop() {
        super.onStop()
        viewModel.loadingLiveData.postValue(true)
        viewModel.loadingLiveData.removeObservers(this)
        viewModel.appsLiveData.postValue(null)
        viewModel.appsLiveData.removeObservers(this)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ListActivity::class.java)
            context.startActivity(intent)
        }
    }
}
