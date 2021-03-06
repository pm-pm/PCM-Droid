package net.blumia.pcm.privatecloudmusic

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.drawer_container.*
import kotlinx.android.synthetic.main.player_controlbar.*
import android.preference.PreferenceActivity
import android.support.v7.widget.LinearLayoutManager
import android.widget.LinearLayout
import kotlinx.android.synthetic.main.content_main.*
import net.blumia.pcm.privatecloudmusic.SQLiteDatabaseOpenHelper.Companion.DB_TABLE_SRV_LIST
import okhttp3.*
import org.jetbrains.anko.db.MapRowParser
import org.jetbrains.anko.db.parseList
import org.jetbrains.anko.db.select
import org.jetbrains.anko.design.snackbar
import java.io.IOException
import android.os.IBinder
import android.os.Message
import android.widget.SeekBar
import org.jetbrains.anko.*
import org.jetbrains.anko.db.delete


class MainActivity : AppCompatActivity(), View.OnClickListener {

    private var curServerItem: ServerItem? = null
    private var prefs: Prefs? = null
    private var player: PlayerService? = null
    private var receiver: TimeIntentReceiver? = null
    private var mIsPlaying: Boolean = false
    var serviceBound = false

    companion object {
        const val Broadcast_PLAY_NEW_AUDIO = "net.blumia.pcm.privatecloudmusic.PlayNewAudio"
        const val ADD_SERVER_REQUEST_CODE = 616
    }

    //Binding this Client to the AudioPlayer Service
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            val binder = service as PlayerService.LocalBinder
            player = binder.service
            serviceBound = true

            toast("Service Bound")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        receiver = TimeIntentReceiver(BroadcastHandler())
        registerReceiver(receiver, IntentFilter(PlayerService.ACTION_UPDATE_TIME))

        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
                this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()

        val serverIconListAdapter = ServerIconListAdapter(this)
        serverIconListAdapter.setOnItemClickListener(object: ServerIconListAdapter.OnItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                curServerItem = serverIconListAdapter.getItem(position)
                if (curServerItem!!.type == ServerType.ADD_SRV) return
                prefs!!.curSrvIndex = position
                tv_cur_server_name.text = curServerItem!!.serverName
                fetchFolderList(curServerItem!!)
            }
        })
        rv_server_icon_list.layoutManager = LinearLayoutManager(this, LinearLayout.VERTICAL, false)
        rv_server_icon_list.adapter = serverIconListAdapter

        val folderListAdapter = FolderListAdapter(this)
        folderListAdapter.setOnItemClickListener(object: FolderListAdapter.OnItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                fetchSongList(folderListAdapter.getItem(position))
            }
        })
        rv_folder_list.layoutManager = LinearLayoutManager(this, LinearLayout.VERTICAL, false)
        rv_folder_list.adapter = folderListAdapter

        val songListAdapter = SongListAdapter(this)
        songListAdapter.setOnItemClickListener(object: SongListAdapter.OnItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                songItemOnClick(songListAdapter.getItem(position), position)
            }
        })
        rv_song_list.layoutManager = LinearLayoutManager(this, LinearLayout.VERTICAL, false)
        rv_song_list.adapter = songListAdapter

        btn_serverPopupMenu.setOnClickListener(this)
        btn_options.setOnClickListener(this)

        btn_music_play_pause.setOnClickListener(this)
        btn_music_prev.setOnClickListener(this)
        btn_music_next.setOnClickListener(this)
        btn_music_loop.setOnClickListener(this)

        btn_exit_app.setOnClickListener { exitAppOnClick() }
        tv_exit_app.setOnClickListener { exitAppOnClick() }

        sb_music_progressbar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekTo(seekBar!!.progress)
            }
        })

        fetchSrvList()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            Log.e("exit", "asd")
            //service is active
            player!!.stopSelf()
        }
        if (receiver != null) {
            unregisterReceiver(receiver)
        }
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        when (item.itemId) {
            R.id.action_settings -> return true
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_serverPopupMenu -> run {
                val popup = PopupMenu(this, v)
                popup.menuInflater.inflate(R.menu.server_options, popup.menu)
                popup.setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.mi_update_server_info -> run {
                            jumpToAddServerActivity()
                            Log.e("test", "update srv info")
                        }
                        R.id.mi_delete_server -> run {
                            Log.e("test", "delete srv")
                            removeServerItemFromDB(curServerItem)
                        }
                    }
                    true
                }
                popup.show()
            }
            R.id.btn_options -> run {
                jumpToSettingActivity()
                drawer_layout.closeDrawer(GravityCompat.START)
            }
            R.id.btn_music_play_pause -> run {
                if (mIsPlaying) pauseAudio() else resumeAudio()
            }
            R.id.btn_music_prev -> run {
                prev()
            }
            R.id.btn_music_next -> run {
                next()
            }
            R.id.btn_music_loop -> run {
                // TODO: should make btn clear to see now is looping or not.
                val curLoopState = prefs!!.guiLoopBtn
                setLoop(!curLoopState)
                toast("Loop: " + !curLoopState)
                prefs!!.guiLoopBtn = !curLoopState
            }
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle?) {
        savedInstanceState!!.putBoolean("ServiceState", serviceBound)
        super.onSaveInstanceState(savedInstanceState)
    }

    public override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        serviceBound = savedInstanceState.getBoolean("ServiceState")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        toast("onActivityResult entered" + resultCode)
        if (resultCode == ADD_SERVER_REQUEST_CODE) {
            fetchSrvList()
        }
    }

    private fun exitAppOnClick() {
        alert(getString(R.string.really_want_to_exit), getString(R.string.confirm_exit)) {
            yesButton { finish() }
            noButton {}
        }.show()
    }

    // region db operate

    fun getServerListDataFromDB(): List<Map<String, Any?>> {
        var srvList:List<Map<String, Any?>> = ArrayList()
        database.use {
            select(DB_TABLE_SRV_LIST).exec {
                srvList = parseList(
                    object: MapRowParser<Map<String, Any?>> {
                        override fun parseRow(columns : Map<String, Any?>) : Map<String, Any?> {
                            //srvList.add(columns)
                            Log.e("asd", columns.toString())
                            return columns
                        }
                    }
                )
            }
        }
        return srvList
    }

    private fun removeServerItemFromDB(item: ServerItem?) {
        if (item == null) return
        database.use {
            delete(DB_TABLE_SRV_LIST, "id = {index}", "index" to item.index)
        }
        fetchSrvList()
    }

    // endregion

    private fun songItemOnClick(item: MusicItem, position: Int) {
        if (item.type == MusicItemType.MUSIC) {
            // do playback
            Log.e("playback", "playback url: " + curServerItem!!.fileRootUrl + prefs!!.curWebFileRelativePath + '/' + item.filePathAndName)
            playAudio(position)
        } else {
            // open folder, for now we ignore the relative path setting
            val type = if (item.type == MusicItemType.SUB_FOLDER) PlaylistType.FOLDER else PlaylistType.PLAYLIST
            fetchSongList(PlaylistItem(item.name, item.filePathAndName, type))
        }
    }

    // region fetch from web / db

    private fun fetchSongList(folderItem: PlaylistItem) {
        if (curServerItem == null) return
        prefs!!.curWebFileRelativePath = folderItem.folderPath

        val folderOrPlaylist = if (folderItem.type == PlaylistType.FOLDER) "folder" else "playlist"
        val httpClient = OkHttpClient()
        val formBody = FormBody.Builder()
                .add("do", "getfilelist")
                .add(folderOrPlaylist, folderItem.folderPath)
                .build()
        val request = Request.Builder()
                .url(curServerItem!!.apiUrl)
                .post(formBody)
                .build()
        httpClient.newCall(request).enqueue(object: okhttp3.Callback {
            override fun onFailure(call: Call?, e: IOException?) {
                e?.printStackTrace()
            }

            override fun onResponse(call: Call?, response: Response?) {
                if (response == null) {
                    snackbar(this@MainActivity.contentView!!, "Server: No Response")
                    return
                }
                if (response.code() != 200) {
                    this@MainActivity.runOnUiThread {
                        snackbar(this@MainActivity.contentView!!, "Server: " + response.code() + " " + response.message())
                    }
                    return
                }
                val result = response.body()!!.string()
                this@MainActivity.runOnUiThread {
                    Log.e("Response", result)

                    val songListAdapter = rv_song_list.adapter as SongListAdapter
                    songListAdapter.updateListFromJsonString(result)
                    prefs!!.playlist = songListAdapter.getPlayList()
                    songListAdapter.notifyDataSetChanged()
                }
            }
        })
    }

    private fun fetchFolderList(srvItem: ServerItem) {
        prefs!!.curWebFileRootPath = srvItem.fileRootUrl.toString()

        val httpClient = OkHttpClient()
        val formBody = FormBody.Builder()
                .add("do", "getfilelist")
                .build()
        val request = Request.Builder()
                .url(srvItem.apiUrl)
                .post(formBody)
                .build()
        httpClient.newCall(request).enqueue(object: okhttp3.Callback {
            override fun onFailure(call: Call?, e: IOException?) {
                e?.printStackTrace()
            }

            override fun onResponse(call: Call?, response: Response?) {
                val result = response!!.body()!!.string()
                Log.e("Response", result)
                this@MainActivity.runOnUiThread {
                    val folderListAdapter = rv_folder_list.adapter as FolderListAdapter
                    folderListAdapter.updateListFromJsonString(result)
                    folderListAdapter.notifyDataSetChanged()

                    if (folderListAdapter.itemCount > 0) {
                        fetchSongList(folderListAdapter.getItem(0))
                    }
                }
            }
        })
    }

    private fun fetchSrvList() {
        val serverIconListAdapter = rv_server_icon_list.adapter as ServerIconListAdapter
        serverIconListAdapter.notifyDataSetChanged() // aww..
        val serverCnt = serverIconListAdapter.itemCount
        if (serverCnt > 0) {
            curServerItem = if (prefs!!.curSrvIndex in 0..(serverCnt - 1)) serverIconListAdapter.getItem(prefs!!.curSrvIndex)
            else {
                prefs!!.curSrvIndex = 0
                serverIconListAdapter.getItem(0)
            }
            tv_cur_server_name.text = curServerItem!!.serverName
            fetchFolderList(curServerItem!!)
        } else {
            // open add server activity?
        }
    }

    // endregion

    private fun jumpToAddServerActivity() {
        val intent = Intent(this, AddServerActivity::class.java)
        startActivityForResult(intent, ADD_SERVER_REQUEST_CODE)
    }

    private fun jumpToSettingActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.GeneralPreferenceFragment::class.java.name)
        intent.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true)
        startActivity(intent)
    }

    //region audio control fun

    private fun playAudio(position: Int) {
        prefs!!.curSongIndex = position
        //Check is service is active
        if (!serviceBound) {
            val playerIntent = Intent(this, PlayerService::class.java)
            startService(playerIntent)
            bindService(playerIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            //Service is active
            //Send media with BroadcastReceiver
            val broadcastIntent = Intent(Broadcast_PLAY_NEW_AUDIO)
            sendBroadcast(broadcastIntent)
        }
    }

    private fun pauseAudio() {
        //Check is service is active
        if (!serviceBound) return

        //Service is active
        //Send media with BroadcastReceiver
        val broadcastIntent = Intent(Broadcast_PLAY_NEW_AUDIO)
        broadcastIntent.putExtra("do", PlayerService.DO_PAUSE) // pause
        sendBroadcast(broadcastIntent)
    }

    private fun resumeAudio() {
        //Check is service is active
        if (!serviceBound) return

        //Service is active
        //Send media with BroadcastReceiver
        val broadcastIntent = Intent(Broadcast_PLAY_NEW_AUDIO)
        broadcastIntent.putExtra("do", PlayerService.DO_RESUME) // resume
        sendBroadcast(broadcastIntent)
    }

    private fun prev() {
        if (!serviceBound) return
        val broadcastIntent = Intent(Broadcast_PLAY_NEW_AUDIO)
        broadcastIntent.putExtra("do", PlayerService.DO_PREV) // prev
        sendBroadcast(broadcastIntent)
    }

    private fun next() {
        if (!serviceBound) return
        val broadcastIntent = Intent(Broadcast_PLAY_NEW_AUDIO)
        broadcastIntent.putExtra("do", PlayerService.DO_NEXT) // next
        sendBroadcast(broadcastIntent)
    }

    private fun seekTo(pos: Int) {
        if (!serviceBound) return
        val broadcastIntent = Intent(Broadcast_PLAY_NEW_AUDIO)
        broadcastIntent.putExtra("do", PlayerService.DO_SEEK) // seek
        broadcastIntent.putExtra("pos", pos)
        sendBroadcast(broadcastIntent)
    }

    private fun setLoop(loop: Boolean) {
        if (!serviceBound) return
        val broadcastIntent = Intent(Broadcast_PLAY_NEW_AUDIO)
        broadcastIntent.putExtra("do", PlayerService.DO_LOOP) // seek
        broadcastIntent.putExtra("loop", loop)
        sendBroadcast(broadcastIntent)
    }

    //endregion

    //region time intent
    class TimeIntentReceiver(private val handler: Handler): BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val message = Message()
            message.obj = intent
            handler.sendMessage(message)
        }
    }
    //endregion

    inner class BroadcastHandler: Handler() {
        override fun handleMessage(msg: Message?) {
            super.handleMessage(msg)
            val intent = msg!!.obj as Intent

            val curTimeStr = intent.getStringExtra("curTime")
            if (!curTimeStr.isNullOrEmpty()) tv_music_cur_time.text = curTimeStr
            val totTimeStr = intent.getStringExtra("totalTime")
            if (!totTimeStr.isNullOrEmpty()) tv_music_total_time.text = totTimeStr
            val titleStr = intent.getStringExtra("songName")
            if (!titleStr.isNullOrEmpty()) tv_music_title.text = titleStr
            if (intent.hasExtra("musicLength")) sb_music_progressbar.max = intent.getIntExtra("musicLength", 0)
            if (intent.hasExtra("progress")) sb_music_progressbar.progress = intent.getIntExtra("progress", 0)
            if (intent.hasExtra("isPlaying")) {
                mIsPlaying = intent.getBooleanExtra("isPlaying", true)
                btn_music_play_pause.background = if (mIsPlaying)
                    applicationContext.getDrawable(R.drawable.ic_pause_white_24dp)
                else
                    applicationContext.getDrawable(R.drawable.ic_play_arrow_white_24dp)
            }
        }
    }
}
