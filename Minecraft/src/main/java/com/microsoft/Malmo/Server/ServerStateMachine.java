package com.microsoft.Malmo.Server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBException;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent.PotentialSpawns;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent;

import org.xml.sax.SAXException;

import com.microsoft.Malmo.MalmoMod;
import com.microsoft.Malmo.MalmoMod.MalmoMessageType;
import com.microsoft.Malmo.MalmoMod.IMalmoMessageListener;
import com.microsoft.Malmo.IState;
import com.microsoft.Malmo.StateEpisode;
import com.microsoft.Malmo.StateMachine;
import com.microsoft.Malmo.MissionHandlerInterfaces.IWorldDecorator.DecoratorException;
import com.microsoft.Malmo.MissionHandlers.MissionBehaviour;
import com.microsoft.Malmo.Schemas.AgentSection;
import com.microsoft.Malmo.Schemas.AgentStart.Inventory;
import com.microsoft.Malmo.Schemas.InventoryBlock;
import com.microsoft.Malmo.Schemas.InventoryItem;
import com.microsoft.Malmo.Schemas.MissionInit;
import com.microsoft.Malmo.Schemas.PosAndDirection;
import com.microsoft.Malmo.Schemas.ServerInitialConditions;
import com.microsoft.Malmo.Schemas.ServerSection;
import com.microsoft.Malmo.Utils.MinecraftTypeHelper;
import com.microsoft.Malmo.Utils.SchemaHelper;
import com.microsoft.Malmo.Utils.ScreenHelper;

/**
 * Class designed to track and control the state of the mod, especially regarding mission launching/running.<br>
 * States are defined by the MissionState enum, and control is handled by MissionStateEpisode subclasses.
 * The ability to set the state directly is restricted, but hooks such as onPlayerReadyForMission etc are exposed to allow
 * subclasses to react to certain state changes.<br>
 * The ProjectMalmo mod app class inherits from this and uses these hooks to run missions.
 */
public class ServerStateMachine extends StateMachine
{
    private MissionInit currentMissionInit = null;   	// The MissionInit object for the mission currently being loaded/run.
    private MissionInit queuedMissionInit = null;		// The MissionInit requested from elsewhere - dormant episode will check for its presence.
    private MissionBehaviour missionHandlers = null;	// The Mission handlers for the mission currently being loaded/run.
    protected String quitCode = "";						// Code detailing the reason for quitting this mission.

    protected void initialiseHandlers(MissionInit init) throws Exception
    {
        this.missionHandlers = MissionBehaviour.createServerHandlersFromMissionInit(init);
    }

    protected MissionBehaviour getHandlers()
    {
        return this.missionHandlers;
    }

    public void setMissionInit(MissionInit minit)
    {
        this.queuedMissionInit = minit;
    }

    public ServerStateMachine(ServerState initialState)
    {
        super(initialState);
        initBusses();
    }

    /** Called to initialise a state machine for a specific Mission request.<br>
     * Most likely caused by the client creating an integrated server.
     * @param initialState Initial state of the machine
     * @param minit The MissionInit object requested
     */
    public ServerStateMachine(ServerState initialState, MissionInit minit)
    {
        super(initialState);
        this.currentMissionInit = minit;
        initBusses();
    }

    private void initBusses()
    {
        // Register ourself on the event busses, so we can harness the server tick:
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    protected String getName() { return "SERVER"; }

    @Override
    protected void onPreStateChange(IState toState)
    {
        String text = "SERVER: " + toState;
        Map<String, String> data = new HashMap<String, String>();
        data.put("text", text);
        data.put("category", ScreenHelper.TextCategory.TXT_SERVER_STATE.name());
        MalmoMod.safeSendToAll(MalmoMessageType.SERVER_TEXT, data);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent ev)
    {
        // Use the server tick to ensure we regularly update our state (from the server thread)
        updateState();
    }

    /** Create the episode object for the requested state.
     * @param state the state the mod is entering
     * @return a MissionStateEpisode that localises all the logic required to run this state
     */
    @Override
    protected StateEpisode getStateEpisodeForState(IState state)
    {
        if (!(state instanceof ServerState))
            return null;

        ServerState sstate = (ServerState)state;
        switch (sstate)
        {
        case WAITING_FOR_MOD_READY:
            return new InitialiseServerModEpisode(this);
        case DORMANT:
            return new DormantEpisode(this);
        case BUILDING_WORLD:
            return new BuildingWorldEpisode(this);
        case WAITING_FOR_AGENTS_TO_ASSEMBLE:
            return new WaitingForAgentsEpisode(this);
        case RUNNING:
            return new RunningEpisode(this);
        case WAITING_FOR_AGENTS_TO_QUIT:
            return new WaitingForAgentsToQuitEpisode(this);
        case ERROR:
            return new ErrorEpisode(this);
        case CLEAN_UP:
            return new CleanUpEpisode(this);
        case MISSION_ENDED:
            return null;//new MissionEndedEpisode(this, MissionResult.ENDED);
        case MISSION_ABORTED:
            return null;//new MissionEndedEpisode(this, MissionResult.AGENT_QUIT);
        default:
            break;
        }
        return null;
    }

    protected MissionInit currentMissionInit()
    {
        return this.currentMissionInit;
    }

    protected boolean hasQueuedMissionInit()
    {
        return this.queuedMissionInit != null;
    }

    protected MissionInit releaseQueuedMissionInit()
    {
        MissionInit minit = null;
        synchronized (this.queuedMissionInit)
        {
            minit = this.queuedMissionInit;
            this.queuedMissionInit = null;
        }
        return minit;
    }

    //---------------------------------------------------------------------------------------------------------
    // Episode helpers - each extends a MissionStateEpisode to encapsulate a certain state
    //---------------------------------------------------------------------------------------------------------

    public abstract class ErrorAwareEpisode extends StateEpisode implements IMalmoMessageListener
    {
        protected Boolean errorFlag = false;
        protected Map<String, String> errorData = null;

        public ErrorAwareEpisode(ServerStateMachine machine)
        {
            super(machine);
            MalmoMod.MalmoMessageHandler.registerForMessage(this, MalmoMessageType.CLIENT_BAILED);
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            if (messageType == MalmoMod.MalmoMessageType.CLIENT_BAILED)
            {
                synchronized(this.errorFlag)
                {
                    this.errorFlag = true;
                    this.errorData = data;
                    onError(data);
                }
            }
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            MalmoMod.MalmoMessageHandler.deregisterForMessage(this, MalmoMessageType.CLIENT_BAILED);
        }

        protected boolean inErrorState()
        {
            synchronized(this.errorFlag)
            {
                return this.errorFlag;
            }
        }

        protected Map<String, String> getErrorData()
        {
            synchronized(this.errorFlag)
            {
                return this.errorData;
            }
        }

        protected void onError(Map<String, String> errorData) {}	// Default does nothing, but can be overridden.
    }

    /** Base class for all episodes that need to be in control of spawning. */
    public abstract class SpawnControlEpisode extends ErrorAwareEpisode
    {
        protected SpawnControlEpisode(ServerStateMachine machine)
        {
            super(machine);
        }

        @Override
        public void onGetPotentialSpawns(PotentialSpawns ps)
        {
            // Decide whether or not to allow spawning.
            if (currentMissionInit() != null && currentMissionInit().getMission() != null)
            {
                ServerSection ss = currentMissionInit().getMission().getServerSection();
                ServerInitialConditions sic = (ss != null) ? ss.getServerInitialConditions() : null;
                Boolean allowSpawning = null;
                if (sic != null)
                    allowSpawning = sic.isAllowSpawning();

                if (allowSpawning == null || allowSpawning)
                    return;	// Allow default behaviour to take place.
            }
            // Cancel spawn event:
            ps.setCanceled(true);
        }
    }

    /** Initial episode - perform client setup */
    public class InitialiseServerModEpisode extends StateEpisode
    {
        ServerStateMachine ssmachine;

        protected InitialiseServerModEpisode(ServerStateMachine machine)
        {
            super(machine);
            this.ssmachine = machine;
        }

        @Override
        protected void execute() throws Exception
        {
        }

        @Override
        protected void onServerTick(ServerTickEvent ev)
        {
            // We wait until we start to get server ticks, at which point we assume Minecraft has finished starting up.
            episodeHasCompleted(ServerState.DORMANT);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Dormant state - receptive to new missions */
    public class DormantEpisode extends SpawnControlEpisode
    {
        private ServerStateMachine ssmachine;

        protected DormantEpisode(ServerStateMachine machine)
        {
            super(machine);
            this.ssmachine = machine;
            if (machine.hasQueuedMissionInit())
            {
                // This is highly suspicious - the queued mission init is a mechanism whereby the client state machine can pass its mission init
                // on to the server - which should only happen if the client has accepted the mission init, which in turn should only happen if the
                // server is dormant.
                // If a mission is queued up now, as we enter the dormant state, that would indicate an error - we've seen this in cases where the client
                // has passed the mission across, then hit an error case and aborted. In such cases this mission is now stale, and should be abandoned.
                // To guard against errors of this sort, simply clear the mission now:
                MissionInit staleMinit = machine.releaseQueuedMissionInit();
                String summary = staleMinit.getMission().getAbout().getSummary();
                System.out.println("SERVER DITCHING SUSPECTED STALE MISSIONINIT: " + summary);
            }
        }

        @Override
        protected void execute()
        {
            // Clear out our error state:
            clearErrorDetails();

            // There are two ways we can receive a mission command. In order of priority, they are:
            // 1: Via a MissionInit object, passed directly in to the state machine's constructor.
            // 2: Requested directly - usually as a result of the client that owns the integrated server needing to pass on its MissionInit.
            // The first of these can be checked for here.
            // The second will be checked for repeatedly during server ticks.
            if (currentMissionInit() != null)
            {
                System.out.println("INCOMING MISSION: Received MissionInit directly through ServerStateMachine constructor.");
                onReceiveMissionInit(currentMissionInit());
            }
        }

        @Override
        protected void onServerTick(TickEvent.ServerTickEvent ev)
        {
            try
            {
                checkForMissionCommand();
            }
            catch (Exception e)
            {
                // TODO: What now?
                e.printStackTrace();
            }
        }

        private void checkForMissionCommand() throws Exception
        {
            // Check whether a mission request has come in "directly":
            if (ssmachine.hasQueuedMissionInit())
            {
                System.out.println("INCOMING MISSION: Received MissionInit directly through queue.");
                onReceiveMissionInit(ssmachine.releaseQueuedMissionInit());
            }
        }

        protected void onReceiveMissionInit(MissionInit missionInit)
        {
            System.out.println("Mission received: " + missionInit.getMission().getAbout().getSummary());
            ChatComponentText txtMission = new ChatComponentText("Received mission: " + EnumChatFormatting.BLUE + missionInit.getMission().getAbout().getSummary());
            ChatComponentText txtSource = new ChatComponentText("Source: " + EnumChatFormatting.GREEN + missionInit.getClientAgentConnection().getAgentIPAddress());
            MinecraftServer.getServer().getConfigurationManager().sendChatMsg(txtMission);
            MinecraftServer.getServer().getConfigurationManager().sendChatMsg(txtSource);

            ServerStateMachine.this.currentMissionInit = missionInit;
            // Create the Mission Handlers
            try
            {
                this.ssmachine.initialiseHandlers(missionInit);
            }
            catch (Exception e)
            {
                // TODO: What?
            }
            // Move on to next state:
            episodeHasCompleted(ServerState.BUILDING_WORLD);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Building world episode - assess world requirements and set up our server accordingly */
    public class BuildingWorldEpisode extends SpawnControlEpisode
    {
        private ServerStateMachine ssmachine;

        protected BuildingWorldEpisode(ServerStateMachine machine)
        {
            super(machine);
            this.ssmachine = machine;
        }

        @Override
        protected void execute()
        {
            MissionBehaviour handlers = this.ssmachine.getHandlers();
            // Assume the world has been created correctly - now do the necessary building.
            boolean builtOkay = true;
            if (handlers != null && handlers.worldDecorator != null)
            {
                try
                {
                    handlers.worldDecorator.buildOnWorld(this.ssmachine.currentMissionInit());
                }
                catch (DecoratorException e)
                {
                    // Error attempting to decorate the world - abandon the mission.
                    builtOkay = false;
                    if (e.getMessage() != null)
                        saveErrorDetails(e.getMessage());
                    // Tell all the clients to abort:
                    Map<String, String>data = new HashMap<String, String>();
                    data.put("message", getErrorDetails());
                    MalmoMod.safeSendToAll(MalmoMessageType.SERVER_ABORT, data);
                    // And abort ourselves:
                    episodeHasCompleted(ServerState.ERROR);
                }
            }
            if (builtOkay)
            {
                // Now set up other attributes of the environment (eg weather)
                initialiseWeather();
                episodeHasCompleted(ServerState.WAITING_FOR_AGENTS_TO_ASSEMBLE);
            }
        }

        private void initialiseWeather()
        {
            ServerSection ss = currentMissionInit().getMission().getServerSection();
            ServerInitialConditions sic = (ss != null) ? ss.getServerInitialConditions() : null;
            if (sic != null && sic.getWeather() != null && !sic.getWeather().equalsIgnoreCase("normal"))
            {
                int maxtime = 1000000 * 20; // Max allowed by Minecraft's own Weather Command.
                int cleartime = (sic.getWeather().equalsIgnoreCase("clear")) ? maxtime : 0;
                int raintime = (sic.getWeather().equalsIgnoreCase("rain")) ? maxtime : 0;
                int thundertime = (sic.getWeather().equalsIgnoreCase("thunder")) ? maxtime : 0;

                WorldServer worldserver = MinecraftServer.getServer().worldServers[0];
                WorldInfo worldinfo = worldserver.getWorldInfo();

                worldinfo.setCleanWeatherTime(cleartime);
                worldinfo.setRainTime(raintime);
                worldinfo.setThunderTime(thundertime);
                worldinfo.setRaining(raintime + thundertime > 0);
                worldinfo.setThundering(thundertime > 0);
            }
        }

        @Override
        protected void onError(Map<String, String> errorData)
        {
            episodeHasCompleted(ServerState.ERROR);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Wait for all agents to stop running and get themselves into a ready state.*/
    public class WaitingForAgentsToQuitEpisode extends SpawnControlEpisode implements MalmoMod.IMalmoMessageListener
    {
        private HashMap<String, Boolean> agentsStopped = new HashMap<String, Boolean>();

        protected WaitingForAgentsToQuitEpisode(ServerStateMachine machine)
        {
            super(machine);
            MalmoMod.MalmoMessageHandler.registerForMessage(this, MalmoMessageType.CLIENT_AGENTSTOPPED);
        }

        @Override
        protected void execute()
        {
            // Get ready to track agent responses:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            for (AgentSection as : agents)
                this.agentsStopped.put(as.getName(), false);

            // Now tell all the agents to stop what they are doing:
            Map<String, String>data = new HashMap<String, String>();
            data.put("QuitCode", ServerStateMachine.this.quitCode);
            MalmoMod.safeSendToAll(MalmoMessageType.SERVER_STOPAGENTS, data);
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            super.onMessage(messageType, data);
            if (messageType == MalmoMod.MalmoMessageType.CLIENT_AGENTSTOPPED)
            {
                String name = data.get("agentname");
                this.agentsStopped.put(name, true);
                if (!this.agentsStopped.containsValue(false))
                {
                    // Agents are all finished and awaiting our message.
                    MalmoMod.safeSendToAll(MalmoMessageType.SERVER_MISSIONOVER);
                    episodeHasCompleted(ServerState.CLEAN_UP);
                }
            }
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            MalmoMod.MalmoMessageHandler.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTSTOPPED);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Wait for all participants to join the game.*/
    public class WaitingForAgentsEpisode extends SpawnControlEpisode implements MalmoMod.IMalmoMessageListener
    {
        private ArrayList<String> pendingReadyAgents = new ArrayList<String>();
        private ArrayList<String> pendingRunningAgents = new ArrayList<String>();
        private HashMap<String, String> usernameToAgentnameMap = new HashMap<String, String>();

        protected WaitingForAgentsEpisode(ServerStateMachine machine)
        {
            super(machine);
            MalmoMod.MalmoMessageHandler.registerForMessage(this,  MalmoMessageType.CLIENT_AGENTREADY);
            MalmoMod.MalmoMessageHandler.registerForMessage(this,  MalmoMessageType.CLIENT_AGENTRUNNING);
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            MalmoMod.MalmoMessageHandler.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTREADY);
            MalmoMod.MalmoMessageHandler.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTRUNNING);
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            super.onMessage(messageType, data);
            if (messageType == MalmoMessageType.CLIENT_AGENTREADY)
            {
                // A client has joined and is waiting for us to tell us it can proceed.
                // Initialise the player, and store a record mapping from the username to the agentname.
                String username = data.get("username");
                String agentname = data.get("agentname");
                if (username != null && agentname != null && this.pendingReadyAgents.contains(agentname))
                {
                    initialisePlayer(username, agentname);
                    this.pendingReadyAgents.remove(agentname);
                    this.usernameToAgentnameMap.put(username, agentname);
                    this.pendingRunningAgents.add(username);

                    // If all clients have now joined, we can tell them to go ahead.
                    if (this.pendingReadyAgents.isEmpty())
                        onCastAssembled();
                }
            }
            else if (messageType == MalmoMessageType.CLIENT_AGENTRUNNING)
            {
                // A client has entered the running state (only happens once all CLIENT_AGENTREADY messages have arrived).
                String username = data.get("username");
                if (username != null && this.pendingRunningAgents.contains(username))
                {
                    this.pendingRunningAgents.remove(username);
                    // If all clients are now running, we can finally enter the running state ourselves.
                    if (this.pendingRunningAgents.isEmpty())
                        episodeHasCompleted(ServerState.RUNNING);
                }
            }
        }

        private AgentSection getAgentSectionFromAgentName(String agentname)
        {
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents != null && agents.size() > 0)
            {
                for (AgentSection ascandidate : agents)
                {
                    if (ascandidate.getName().equals(agentname))
                        return ascandidate;
                }
            }
            return null;
        }

        private EntityPlayerMP getPlayerFromUsername(String username)
        {
            ServerConfigurationManager scoman = MinecraftServer.getServer().getConfigurationManager();
            EntityPlayerMP player = scoman.getPlayerByUsername(username);
            return player;
        }

        private void initialisePlayer(String username, String agentname)
        {
            AgentSection as = getAgentSectionFromAgentName(agentname);
            EntityPlayerMP player = getPlayerFromUsername(username);

            if (player != null && as != null)
            {
                if ((player.getHealth() <= 0 || player.isDead || !player.isEntityAlive()))
                {
                    player.markPlayerActive();
                    player = MinecraftServer.getServer().getConfigurationManager().recreatePlayerEntity(player, player.dimension, false);
                    player.playerNetServerHandler.playerEntity = player;
                }

                // Reset their food and health:
                player.setHealth(player.getMaxHealth());
                player.getFoodStats().addStats(20, 40);
                player.maxHurtResistantTime = 1; // Set this to a low value so that lava will kill the player straight away.
                player.extinguish();	// In case the player was left burning.

                // Set their initial position and speed:
                PosAndDirection pos = as.getAgentStart().getPlacement();
                if (pos != null) {
                    player.rotationYaw = pos.getYaw().floatValue();
                    player.rotationPitch = pos.getPitch().floatValue();
                    player.setPositionAndUpdate(pos.getX().doubleValue() + 0.5,pos.getY().doubleValue(),pos.getZ().doubleValue() + 0.5);
                    player.onUpdate();	// Needed to force scene to redraw
                }
                player.setVelocity(0, 0, 0);	// Minimise chance of drift!

                // Set their inventory:
                if (as.getAgentStart().getInventory() != null)
                    initialiseInventory(player, as.getAgentStart().getInventory());

                // Set their game mode to spectator for now, to protect them while we wait for the rest of the cast to assemble:
                player.setGameType(GameType.SPECTATOR);

                // Set the custom name.
                // SetAgentNameMessage.SetAgentNameActor actor = new SetAgentNameMessage.SetAgentNameActor(player, agentname);
                // actor.go();
            }
        }

        @Override
        protected void execute()
        {
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents != null && agents.size() > 0)
            {
                System.out.println("Experiment requires: ");
                for (AgentSection as : agents)
                {
                    System.out.println(">>>> " + as.getName());
                    pendingReadyAgents.add(as.getName());
                }
            }
        }

        private void resetPlayerGameTypes()
        {
            // Go through and set all the players to their correct game type:
            for (Map.Entry<String, String> entry : this.usernameToAgentnameMap.entrySet())
            {
                AgentSection as = getAgentSectionFromAgentName(entry.getValue());
                EntityPlayerMP player = getPlayerFromUsername(entry.getKey());
                if (as != null && player != null)
                {
                    player.setGameType(GameType.getByName(as.getMode().name().toLowerCase()));
                    // Also make sure we haven't accidentally left the player flying:
                    player.capabilities.isFlying = false;
                    player.sendPlayerAbilities();
                }
            }
        }

        private void onCastAssembled()
        {
            // Ready the players:
            resetPlayerGameTypes();
            // And tell them all they can proceed:
            MalmoMod.safeSendToAll(MalmoMessageType.SERVER_ALLPLAYERSJOINED);
        }

        @Override
        protected void onError(Map<String, String> errorData)
        {
            // Something has gone wrong - one of the clients has been forced to bail.
            // Do some tidying:
            resetPlayerGameTypes();
            // And tell all the clients to abort:
            MalmoMod.safeSendToAll(MalmoMessageType.SERVER_ABORT);
            // And abort ourselves:
            episodeHasCompleted(ServerState.ERROR);
        }

        private void initialiseInventory(EntityPlayerMP player, Inventory inventory)
        {
            // Clear inventory:
            player.inventory.func_174925_a(null, -1, -1, null);
            player.inventoryContainer.detectAndSendChanges();
            if (!player.capabilities.isCreativeMode)
                player.updateHeldItem();

            // Now add specified items:
            List<Object> objects = inventory.getInventoryItemOrInventoryBlock();
            for (Object obj : objects)
            {
                if (obj instanceof InventoryBlock)
                {
                    InventoryBlock invblock = (InventoryBlock)obj;
                    IBlockState block = MinecraftTypeHelper.ParseBlockType(invblock.getType().value());
                    if( block != null )
                        player.inventory.setInventorySlotContents(invblock.getSlot(), new ItemStack(block.getBlock(), invblock.getQuantity()));

                }
                else if (obj instanceof InventoryItem)
                {
                    InventoryItem invitem = (InventoryItem)obj;
                    Item item = MinecraftTypeHelper.ParseItemType(invitem.getType().value());
                    if( item != null )
                        player.inventory.setInventorySlotContents(invitem.getSlot(), new ItemStack(item, invitem.getQuantity()));
                }
            }
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Mission running state.
     */
    public class RunningEpisode extends SpawnControlEpisode
    {
        ArrayList<String> runningAgents = new ArrayList<String>();
        boolean missionHasEnded = false;

        protected RunningEpisode(ServerStateMachine machine)
        {
            super(machine);

            // Build up list of running agents:
            List<AgentSection> agents = currentMissionInit().getMission().getAgentSection();
            if (agents != null && agents.size() > 0)
            {
                for (AgentSection as : agents)
                {
                    runningAgents.add(as.getName());
                }
            }

            // And register for the agent-finished message:
            MalmoMod.MalmoMessageHandler.registerForMessage(this, MalmoMessageType.CLIENT_AGENTFINISHEDMISSION);
        }

        @Override
        public void cleanup()
        {
            super.cleanup();
            MalmoMod.MalmoMessageHandler.deregisterForMessage(this, MalmoMessageType.CLIENT_AGENTFINISHEDMISSION);
        }

        @Override
        public void onMessage(MalmoMessageType messageType, Map<String, String> data)
        {
            super.onMessage(messageType, data);
            if (messageType == MalmoMessageType.CLIENT_AGENTFINISHEDMISSION)
            {
                String agentName = data.get("agentname");
                if (agentName != null)
                {
                    this.runningAgents.remove(agentName);
                }
            }
        }

        @Override
        protected void execute()
        {
            // Set up some initial conditions:
            ServerSection ss = currentMissionInit().getMission().getServerSection();
            ServerInitialConditions sic = (ss != null) ? ss.getServerInitialConditions() : null;
            if (sic != null && sic.getTime() != null)
            {
                MinecraftServer server = MinecraftServer.getServer();
                if (server.worldServers != null && server.worldServers.length != 0)
                {
                    for (int i = 0; i < MinecraftServer.getServer().worldServers.length; ++i)
                    {
                        World world = MinecraftServer.getServer().worldServers[i];
                        boolean allowTimeToPass = (sic.getTime().isAllowPassageOfTime() != Boolean.FALSE);	// Defaults to true if unspecified.
                        world.getGameRules().setOrCreateGameRule("doDaylightCycle", allowTimeToPass ? "true" : "false");
                        world.setWorldTime(sic.getTime().getStartTime());
                    }
                }
            }
            if (getHandlers().quitProducer != null)
                getHandlers().quitProducer.prepare(currentMissionInit());
        }

        @Override
        protected void onServerTick(ServerTickEvent ev)
        {
            if (this.missionHasEnded)
                return;	// In case we get in here after deciding the mission is over.

            if (ev.phase == Phase.END && getHandlers() != null && getHandlers().worldDecorator != null)
            {
                MinecraftServer server = MinecraftServer.getServer();
                if (server.worldServers != null && server.worldServers.length != 0)
                {
                    World world = server.getEntityWorld();
                    getHandlers().worldDecorator.update(world);
                }
            }

            if (ev.phase == Phase.END)
            {
                if (getHandlers() != null && getHandlers().quitProducer != null && getHandlers().quitProducer.doIWantToQuit(currentMissionInit()))
                {
                    ServerStateMachine.this.quitCode = getHandlers().quitProducer.getOutcome();
                    onMissionEnded();
                }
                else if (this.runningAgents.isEmpty())
                {
                    ServerStateMachine.this.quitCode = "All agents finished";
                    onMissionEnded();
                }
            }
        }

        private void onMissionEnded()
        {
            this.missionHasEnded = true;

            if (getHandlers().quitProducer != null)
                getHandlers().quitProducer.cleanup();

            // Mission is over - wait for all agents to stop.
            episodeHasCompleted(ServerState.WAITING_FOR_AGENTS_TO_QUIT);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    /** Generic error state */
    public class ErrorEpisode extends StateEpisode
    {
        public ErrorEpisode(StateMachine machine)
        {
            super(machine);
        }
        @Override
        protected void execute()
        {
            //TODO - tidy up.
            episodeHasCompleted(ServerState.CLEAN_UP);
        }
    }

    //---------------------------------------------------------------------------------------------------------
    public class CleanUpEpisode extends StateEpisode
    {
        public CleanUpEpisode(StateMachine machine)
        {
            super(machine);
        }
        @Override
        protected void execute()
        {
            // Put in all cleanup code here.
            ServerStateMachine.this.currentMissionInit = null;
            episodeHasCompleted(ServerState.DORMANT);
        }
    }
}