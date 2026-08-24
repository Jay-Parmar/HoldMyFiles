package dev.jay.holdmyfiles.ui.home

import dev.jay.holdmyfiles.core.ServerStatus
import dev.jay.holdmyfiles.core.security.RandomNumberSource
import dev.jay.holdmyfiles.core.security.RunPinGenerator
import dev.jay.holdmyfiles.core.storage.ShareId
import dev.jay.holdmyfiles.service.SharingController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an enabled folder starts only after platform permissions are resolved`() = runTest(dispatcher) {
        val source = FakeShareSource(
            listOf(
                ShareRowUi(
                    id = ShareId("photos"),
                    label = "Photos",
                    enabled = true,
                ),
            ),
        )
        val controller = FakeSharingController()
        val viewModel = OwnerHomeViewModel(source, NoOpFolderManager, controller)
        advanceUntilIdle()

        viewModel.onAction(HomeAction.StartSharing)
        assertEquals(HomeEffect.RequestStartPermissions, viewModel.effects.awaitItem())
        assertEquals(0, controller.startCount)

        viewModel.startPermissionsResolved(localNetworkGranted = true)
        advanceUntilIdle()

        assertEquals(1, controller.startCount)
        assertTrue(viewModel.state.value.serverStatus is ServerStatus.Starting)
    }

    @Test
    fun `removing a folder during a run does not stop sharing`() = runTest(dispatcher) {
        val shareId = ShareId("photos")
        val source = FakeShareSource(
            listOf(ShareRowUi(id = shareId, label = "Photos", enabled = true)),
        )
        val folderManager = RecordingFolderManager()
        val running = runningStatus()
        val controller = FakeSharingController(running)
        val viewModel = OwnerHomeViewModel(source, folderManager, controller)
        advanceUntilIdle()

        viewModel.onAction(HomeAction.RequestRemove(shareId))
        viewModel.onAction(HomeAction.ConfirmRemove)
        advanceUntilIdle()

        assertEquals(listOf(shareId), folderManager.removedIds)
        assertEquals(0, controller.stopCount)
        assertSame(running, viewModel.state.value.serverStatus)
    }

    @Test
    fun `only an explicit stop action stops a running service`() = runTest(dispatcher) {
        val controller = FakeSharingController(runningStatus())
        val viewModel = OwnerHomeViewModel(
            FakeShareSource(emptyList()),
            NoOpFolderManager,
            controller,
        )
        advanceUntilIdle()

        viewModel.onAction(HomeAction.StopSharing)
        advanceUntilIdle()

        assertEquals(1, controller.stopCount)
        assertTrue(viewModel.state.value.serverStatus is ServerStatus.Stopping)
    }

    @Test
    fun `copy pin effect retains a typed redacted pin`() = runTest(dispatcher) {
        val running = runningStatus()
        val viewModel = OwnerHomeViewModel(
            FakeShareSource(emptyList()),
            NoOpFolderManager,
            FakeSharingController(running),
        )
        advanceUntilIdle()

        viewModel.onAction(HomeAction.CopyPin)
        val effect = viewModel.effects.awaitItem() as HomeEffect.CopyPin

        assertSame(running.pin, effect.pin)
        assertTrue(effect.toString().contains("redacted"))
        assertTrue(!effect.toString().contains("123456"))
    }
}

private class FakeShareSource(initial: List<ShareRowUi>) : OwnerShareSource {
    private val mutableShares = MutableStateFlow(initial)

    override val shares: Flow<List<ShareRowUi>> = mutableShares
}

private object NoOpFolderManager : OwnerFolderManager {
    override suspend fun addPickedTree(treeUri: android.net.Uri): OwnerMutationFailure? = null

    override suspend fun setEnabled(id: ShareId, enabled: Boolean): OwnerMutationFailure? = null

    override suspend fun remove(id: ShareId): OwnerMutationFailure? = null
}

private class RecordingFolderManager : OwnerFolderManager {
    val removedIds = mutableListOf<ShareId>()

    override suspend fun addPickedTree(treeUri: android.net.Uri): OwnerMutationFailure? = null

    override suspend fun setEnabled(id: ShareId, enabled: Boolean): OwnerMutationFailure? = null

    override suspend fun remove(id: ShareId): OwnerMutationFailure? {
        removedIds += id
        return null
    }
}

private class FakeSharingController(
    initialStatus: ServerStatus = ServerStatus.Stopped,
) : SharingController {
    private val mutableStatus = MutableStateFlow(initialStatus)

    override val status = mutableStatus

    var startCount = 0
        private set

    var stopCount = 0
        private set

    override fun start() {
        startCount += 1
        mutableStatus.value = ServerStatus.Starting
    }

    override fun stop() {
        stopCount += 1
        mutableStatus.value = ServerStatus.Stopping
    }
}

private suspend fun <T> Flow<T>.awaitItem(): T = first()

private fun runningStatus(): ServerStatus.Running = ServerStatus.Running(
    address = "192.168.1.4",
    port = 12_345,
    pin = RunPinGenerator(RandomNumberSource { 123_456 }).generate(),
)
