package technology.rocketjump.mountaincore.entities.behaviour.furniture;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.ai.msg.MessageDispatcher;
import com.badlogic.gdx.math.GridPoint2;
import com.google.common.collect.Lists;
import org.pmw.tinylog.Logger;
import technology.rocketjump.mountaincore.entities.components.InventoryComponent;
import technology.rocketjump.mountaincore.entities.components.ItemAllocation;
import technology.rocketjump.mountaincore.entities.components.ItemAllocationComponent;
import technology.rocketjump.mountaincore.entities.components.LiquidContainerComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.physical.furniture.FurnitureEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.furniture.FurnitureLayout;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemEntityAttributes;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.jobs.model.Job;
import technology.rocketjump.mountaincore.jobs.model.JobState;
import technology.rocketjump.mountaincore.materials.model.GameMaterial;
import technology.rocketjump.mountaincore.messaging.MessageType;
import technology.rocketjump.mountaincore.messaging.types.RequestHaulingAllocationMessage;
import technology.rocketjump.mountaincore.messaging.types.RequestHaulingMessage;
import technology.rocketjump.mountaincore.messaging.types.TransformItemMessage;
import technology.rocketjump.mountaincore.persistence.EnumParser;
import technology.rocketjump.mountaincore.persistence.SavedGameDependentDictionaries;
import technology.rocketjump.mountaincore.persistence.model.InvalidSaveException;
import technology.rocketjump.mountaincore.persistence.model.SavedGameStateHolder;
import technology.rocketjump.mountaincore.rooms.HaulingAllocation;
import technology.rocketjump.mountaincore.ui.i18n.I18nString;
import technology.rocketjump.mountaincore.ui.i18n.I18nText;
import technology.rocketjump.mountaincore.ui.i18n.I18nTranslator;
import technology.rocketjump.mountaincore.ui.i18n.I18nWord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static technology.rocketjump.mountaincore.entities.behaviour.furniture.MushroomShockTankBehaviour.MushrooomShockTankState.*;

public class MushroomShockTankBehaviour extends FillLiquidContainerBehaviour implements SelectableDescription, HoldsLimitedItems {

	/** Both tanks built on this behaviour are two tiles wide, so they take two at a time */
	public static final int ITEM_CAPACITY = 2;

	private MushrooomShockTankState state = WAITING_FOR_LIQUID;
	double TIME_TO_SHOCK_MUSHROOM_LOG;
	private List<Job> haulingJobs = new ArrayList<>();

	@Override
	public int getItemCapacity() {
		return ITEM_CAPACITY;
	}

	@Override
	public void init(Entity parentEntity, MessageDispatcher messageDispatcher, GameContext gameContext) {
		super.init(parentEntity, messageDispatcher, gameContext);
		TIME_TO_SHOCK_MUSHROOM_LOG = gameContext.getConstantsRepo().getSettlementConstants().getMushroomShockTimeHours();
	}

	@Override
	public void destroy(Entity parentEntity, MessageDispatcher messageDispatcher, GameContext gameContext) {
		super.destroy(parentEntity, messageDispatcher, gameContext);
	}

	@Override
	public void infrequentUpdate(GameContext gameContext) {
		super.infrequentUpdate(gameContext);
		if (parentEntity.isOnFire()) {
			return;
		}

		LiquidContainerComponent liquidContainerComponent = parentEntity.getComponent(LiquidContainerComponent.class);
		InventoryComponent inventoryComponent = parentEntity.getOrCreateComponent(InventoryComponent.class);
		// One bundle to a place. Left to merge, arrivals pile into a single slot and restart that
		// stack's clock each time, which is the opposite of timing each one from when it went in
		inventoryComponent.setNoMerging(true);
		haulingJobs.removeIf(job -> job.getJobState().equals(JobState.REMOVED));

		// Every item soaks on its own clock, taken from when it went in, so each one is dealt with
		// in its own right rather than the tank carrying a single state for whatever is inside it
		for (InventoryComponent.InventoryEntry entry : new ArrayList<>(inventoryComponent.getInventoryEntries())) {
			if (isSoaking(entry) && soakingProgress(entry, gameContext) >= 1f) {
				turnIntoProduct(entry);
			}
		}

		if (!relatedJobTypes.isEmpty()) {
			for (InventoryComponent.InventoryEntry entry : new ArrayList<>(inventoryComponent.getInventoryEntries())) {
				if (isFinished(entry) && !alreadyBeingCollected(entry)) {
					haulItemOut(entry);
				}
			}
		}

		boolean filled = liquidContainerComponent.getLiquidQuantity() >= liquidContainerComponent.getMaxLiquidCapacity();
		// Bounded rather than looping on the reply, which arrives from a message and cannot be trusted
		// to always come back
		for (int attempt = 0; filled && attempt < ITEM_CAPACITY; attempt++) {
			if (placesTaken(inventoryComponent) >= ITEM_CAPACITY || !attemptToAssignItemToSelf(gameContext)) {
				break;
			}
		}

		state = stateOf(filled, inventoryComponent);
	}

	private void turnIntoProduct(InventoryComponent.InventoryEntry entry) {
		messageDispatcher.dispatchMessage(MessageType.TRANSFORM_ITEM_TYPE, new TransformItemMessage(entry.entity, relatedItemTypes.get(2)));
		entry.entity.getOrCreateComponent(ItemAllocationComponent.class).cancelAll(ItemAllocation.Purpose.HELD_IN_INVENTORY);
		if (!relatedMaterials.isEmpty()) {
			ItemEntityAttributes attributes = (ItemEntityAttributes) entry.entity.getPhysicalEntityComponent().getAttributes();
			for (GameMaterial material : relatedMaterials) {
				attributes.setMaterial(material);
			}
		}
	}

	/**
	 * AVAILABLE has to go on meaning "room for one more", because the mushroom farm reads it to
	 * decide whether to send another log over.
	 */
	private MushrooomShockTankState stateOf(boolean filled, InventoryComponent inventoryComponent) {
		if (!filled) {
			return WAITING_FOR_LIQUID;
		} else if (placesTaken(inventoryComponent) < ITEM_CAPACITY) {
			return AVAILABLE;
		} else if (getSecondaryItemFromInventory() != null) {
			return LOG_SHOCK_COMPLETE;
		} else {
			return ASSIGNED;
		}
	}

	/**
	 * Counted in bundles rather than in slots, so a stack left over from an older save is honoured
	 * for what it holds instead of passing for one.
	 */
	private int placesTaken(InventoryComponent inventoryComponent) {
		int taken = 0;
		for (InventoryComponent.InventoryEntry entry : inventoryComponent.getInventoryEntries()) {
			taken += quantityOf(entry.entity);
		}
		for (Job job : haulingJobs) {
			HaulingAllocation allocation = job.getHaulingAllocation();
			if (allocation != null && allocation.getTargetId() != null && parentEntity.getId() == allocation.getTargetId()) {
				taken += allocation.getItemAllocation() == null ? 1 : Math.max(1, allocation.getItemAllocation().getAllocationAmount());
			}
		}
		return taken;
	}

	private int quantityOf(Entity item) {
		if (item.getPhysicalEntityComponent().getAttributes() instanceof ItemEntityAttributes attributes) {
			return Math.max(1, attributes.getQuantity());
		}
		return 1;
	}

	private boolean alreadyBeingCollected(InventoryComponent.InventoryEntry entry) {
		return haulingJobs.stream()
				.filter(job -> job.getHaulingAllocation() != null)
				.anyMatch(job -> entry.entity.getId() == (job.getHaulingAllocation().getHauledEntityId() == null ? -1L : job.getHaulingAllocation().getHauledEntityId()));
	}

	private boolean isSoaking(InventoryComponent.InventoryEntry entry) {
		return isOfType(entry, relatedItemTypes.get(1));
	}

	private boolean isFinished(InventoryComponent.InventoryEntry entry) {
		return isOfType(entry, relatedItemTypes.get(2));
	}

	private boolean isOfType(InventoryComponent.InventoryEntry entry, ItemType itemType) {
		if (itemType == null || !(entry.entity.getPhysicalEntityComponent().getAttributes() instanceof ItemEntityAttributes attributes)) {
			return false;
		}
		return itemType.equals(attributes.getItemType());
	}

	private void haulItemOut(InventoryComponent.InventoryEntry inventoryEntry) {
		messageDispatcher.dispatchMessage(MessageType.REQUEST_ENTITY_HAULING, new RequestHaulingMessage(
				inventoryEntry.entity, parentEntity, true, priority, job -> {
					if (job != null) {
						haulingJobs.add(job);
					}
				}
			)
		);
	}

	public MushrooomShockTankState getState() {
		return state;
	}


	/**
	 * One line per item, since they no longer share a single progress between them.
	 */
	@Override
	public List<I18nText> getDescription(I18nTranslator i18nTranslator, GameContext gameContext, MessageDispatcher messageDispatcher) {
		InventoryComponent inventoryComponent = parentEntity.getOrCreateComponent(InventoryComponent.class);
		List<I18nText> lines = new ArrayList<>();

		if (WAITING_FOR_LIQUID.equals(state)) {
			return Lists.newArrayList(i18nTranslator.getTranslatedWordWithReplacements(
					WAITING_FOR_LIQUID.descriptionI18nKey, descriptionReplacements(i18nTranslator, 0f)));
		}

		for (InventoryComponent.InventoryEntry entry : inventoryComponent.getInventoryEntries()) {
			if (isSoaking(entry)) {
				lines.add(i18nTranslator.getTranslatedWordWithReplacements(ASSIGNED.descriptionI18nKey,
						descriptionReplacements(i18nTranslator, 100f * soakingProgress(entry, gameContext))));
			} else if (isFinished(entry)) {
				lines.add(i18nTranslator.getTranslatedWordWithReplacements(LOG_SHOCK_COMPLETE.descriptionI18nKey,
						descriptionReplacements(i18nTranslator, 100f)));
			}
		}

		if (placesTaken(inventoryComponent) < ITEM_CAPACITY) {
			lines.add(i18nTranslator.getTranslatedWordWithReplacements(AVAILABLE.descriptionI18nKey,
					descriptionReplacements(i18nTranslator, 0f)));
		}
		return lines;
	}

	private Map<String, I18nString> descriptionReplacements(I18nTranslator i18nTranslator, float progress) {
		Map<String, I18nString> replacements = new HashMap<>();
		replacements.put("progress", new I18nWord("progress", I18nTranslator.oneDecimalFormat.format(progress)));
		replacements.put("itemType", i18nTranslator.getDictionary().getWord(relatedItemTypes.get(1).getI18nKey()));
		return replacements;
	}

	/**
	 * @return whether something was actually sent for, so the caller knows to stop asking
	 */
	private boolean attemptToAssignItemToSelf(GameContext gameContext) {
		if (relatedItemTypes.get(1) == null || relatedJobTypes.isEmpty()) {
			Logger.error("Missing related info in " +getClass().getSimpleName());
			return false;
		}
		ItemType targetItemType = relatedItemTypes.get(1);
		boolean[] sentFor = new boolean[1];
		messageDispatcher.dispatchMessage(MessageType.REQUEST_HAULING_ALLOCATION, new RequestHaulingAllocationMessage(
				parentEntity, parentEntity.getLocationComponent().getWorldPosition(), targetItemType, null,
				true, 1, null, haulingAllocation -> {
					if (haulingAllocation != null) {
						Job haulingJob = new Job(relatedJobTypes.get(0));
						haulingJob.setJobPriority(priority);
						haulingJob.setTargetId(haulingAllocation.getTargetId());
						haulingJob.setJobLocation(collectedFrom(haulingAllocation, gameContext));
						haulingJob.setHaulingAllocation(haulingAllocation);
//						haulingJob.setRequiredProfession(mushroomFarmingProfession);

						this.haulingJobs.add(haulingJob);
						messageDispatcher.dispatchMessage(MessageType.JOB_CREATED, haulingJob);
						sentFor[0] = true;
					}
				}
			)
		);
		return sentFor[0];
	}

	/**
	 * A bundle sitting in a chest is reached from one of the chest's walkable sides, never from the
	 * chest's own tile, which nobody can stand on.
	 */
	private GridPoint2 collectedFrom(HaulingAllocation allocation, GameContext gameContext) {
		if (HaulingAllocation.AllocationPositionType.FURNITURE.equals(allocation.getSourcePositionType())
				&& allocation.getSourceContainerId() != null) {
			Entity container = gameContext.getEntities().get(allocation.getSourceContainerId());
			if (container != null && container.getPhysicalEntityComponent().getAttributes() instanceof FurnitureEntityAttributes) {
				FurnitureLayout.Workspace workspace = FurnitureLayout.getAnyNavigableWorkspace(container, gameContext.getAreaMap());
				if (workspace != null) {
					return workspace.getAccessedFrom();
				}
			}
		}
		return allocation.getSourcePosition();
	}

	float soakingProgress(InventoryComponent.InventoryEntry entry, GameContext gameContext) {
		double soakedFor = gameContext.getGameClock().getCurrentGameTime() - entry.getLastUpdateGameTime();
		if (soakedFor >= TIME_TO_SHOCK_MUSHROOM_LOG) {
			return 1f;
		}
		return (float) Math.max(0d, soakedFor / TIME_TO_SHOCK_MUSHROOM_LOG);
	}

	private InventoryComponent.InventoryEntry getSecondaryItemFromInventory() {
		InventoryComponent inventoryComponent = parentEntity.getOrCreateComponent(InventoryComponent.class);
		if (!inventoryComponent.isEmpty()) {
			for (InventoryComponent.InventoryEntry inventoryEntry : inventoryComponent.getInventoryEntries()) {
				ItemEntityAttributes attributes = (ItemEntityAttributes) inventoryEntry.entity.getPhysicalEntityComponent().getAttributes();
				if (attributes.getItemType().equals(relatedItemTypes.get(2))) {
					return inventoryEntry;
				}
			}
		}
		return null;
	}

	public void setState(MushrooomShockTankState state) {
		this.state = state;
	}

	public List<Job> getHaulingJobs() {
		return haulingJobs;
	}

	@Override
	public void writeTo(JSONObject asJson, SavedGameStateHolder savedGameStateHolder) {
		super.writeTo(asJson, savedGameStateHolder);

		if (!WAITING_FOR_LIQUID.equals(state)) {
			asJson.put("state", state.name());
		}

		if (!haulingJobs.isEmpty()) {
			JSONArray incomingJobsJson = new JSONArray();
			for (Job incomingHaulingJob : haulingJobs) {
				incomingJobsJson.add(incomingHaulingJob.getJobId());
			}
			asJson.put("incomingHaulingJobs", incomingJobsJson);
		}
	}

	@Override
	public void readFrom(JSONObject asJson, SavedGameStateHolder savedGameStateHolder, SavedGameDependentDictionaries relatedStores) throws InvalidSaveException {
		super.readFrom(asJson, savedGameStateHolder, relatedStores);

		JSONArray incomingJobsJson = asJson.getJSONArray("incomingHaulingJobs");
		if (incomingJobsJson != null) {
			for (int cursor = 0; cursor < incomingJobsJson.size(); cursor++) {
				Long jobId = incomingJobsJson.getLong(cursor);
				Job job = savedGameStateHolder.jobs.get(jobId);
				if (job != null) {
					haulingJobs.add(job);
				} else {
					throw new InvalidSaveException("Could not find job with ID " + jobId);
				}
			}
		}

		this.state = EnumParser.getEnumValue(asJson, "state", MushrooomShockTankState.class, WAITING_FOR_LIQUID);
	}

	public enum MushrooomShockTankState {

		WAITING_FOR_LIQUID("FURNITURE.DESCRIPTION.WAITING_FOR_LIQUID"),
		AVAILABLE("FURNITURE.DESCRIPTION.WAITING_FOR_ITEM"),
		ASSIGNED("FURNITURE.DESCRIPTION.SHOCKING_IN_PROGRESS"),
		LOG_SHOCK_COMPLETE("FURNITURE.DESCRIPTION.SHOCKING_IN_PROGRESS");

		public final String descriptionI18nKey;

		MushrooomShockTankState(String descriptionI18nKey) {
			this.descriptionI18nKey = descriptionI18nKey;
		}
	}
}
