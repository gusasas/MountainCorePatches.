package technology.rocketjump.mountaincore.rooms.tags;

import com.badlogic.gdx.ai.msg.MessageDispatcher;
import technology.rocketjump.mountaincore.entities.components.furniture.FurnitureStockpileComponent;
import technology.rocketjump.mountaincore.entities.model.Entity;
import technology.rocketjump.mountaincore.entities.model.EntityType;
import technology.rocketjump.mountaincore.entities.model.physical.combat.DefenseType;
import technology.rocketjump.mountaincore.entities.model.physical.item.ItemType;
import technology.rocketjump.mountaincore.entities.tags.InventoryItemsUnallocatedTag;
import technology.rocketjump.mountaincore.entities.tags.Tag;
import technology.rocketjump.mountaincore.entities.tags.TagProcessingUtils;
import technology.rocketjump.mountaincore.gamecontext.GameContext;
import technology.rocketjump.mountaincore.mapping.tile.MapTile;
import technology.rocketjump.mountaincore.production.FurnitureStockpile;
import technology.rocketjump.mountaincore.production.StockpileComponentUpdater;
import technology.rocketjump.mountaincore.production.StockpileSettings;
import technology.rocketjump.mountaincore.rooms.Room;
import technology.rocketjump.mountaincore.rooms.components.StockpileRoomComponent;
import technology.rocketjump.mountaincore.rooms.components.behaviour.TradeDepotBehaviour;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StockpileTag extends Tag {

	private static final Pattern IS_MACRO_PATTERN = Pattern.compile("IS_(ARMOUR|WEAPON|SHIELD)");
	private static final Pattern ITEM_TYPE_PATTERN = Pattern.compile("ItemType_(.*)");

	@Override
	public String getTagName() {
		return "STOCKPILE";
	}

	@Override
	public boolean isValid(TagProcessingUtils tagProcessingUtils) {
		boolean isValid = true;
		if (args.size() > 0) {
			isValid = args.get(0).matches("\\d+");
		}
		for (int i = 1; i < args.size(); i++) {
			String argument = args.get(i);
			isValid = IS_MACRO_PATTERN.matcher(argument).matches() || ITEM_TYPE_PATTERN.matcher(argument).matches();
		}
		return isValid;
	}

	/**
	 * @return the stockpile settings of the room this entity sits in, or null when it is not in a stockpile
	 */
	private StockpileSettings settingsOfContainingRoom(Entity entity, GameContext gameContext) {
		if (gameContext == null || gameContext.getAreaMap() == null || entity.getLocationComponent() == null) {
			return null;
		}
		MapTile tile = gameContext.getAreaMap().getTile(entity.getLocationComponent().getWorldOrParentPosition());
		if (tile == null || tile.getRoomTile() == null || tile.getRoomTile().getRoom() == null) {
			return null;
		}
		StockpileRoomComponent stockpileRoomComponent = tile.getRoomTile().getRoom().getComponent(StockpileRoomComponent.class);
		return stockpileRoomComponent == null ? null : stockpileRoomComponent.getStockpileSettings();
	}

	@Override
	public void apply(Room room, TagProcessingUtils tagProcessingUtils) {
		room.createComponent(StockpileRoomComponent.class, tagProcessingUtils.messageDispatcher);
	}

	/**
	 * What a chest standing in a trade depot is allowed to hold, whatever it holds elsewhere.
	 */
	private static final String TRADE_DEPOT_CONTENTS = "Treasure-Coin";

	private boolean isInTradeDepot(Entity entity, GameContext gameContext) {
		if (gameContext == null || gameContext.getAreaMap() == null || entity.getLocationComponent() == null ||
				entity.getLocationComponent().getWorldOrParentPosition() == null) {
			return false;
		}
		MapTile tile = gameContext.getAreaMap().getTile(entity.getLocationComponent().getWorldOrParentPosition());
		if (tile == null || tile.getRoomTile() == null || tile.getRoomTile().getRoom() == null) {
			return false;
		}
		return tile.getRoomTile().getRoom().getComponent(TradeDepotBehaviour.class) != null;
	}

	/**
	 * A chest in a trade depot takes the traders' coins and nothing else. Left open to everything, as
	 * a chest built anywhere else now is, the settlers fill the depot with whatever is nearest.
	 */
	private void restrictToDepotContents(Entity entity, TagProcessingUtils tagProcessingUtils, GameContext gameContext) {
		FurnitureStockpileComponent component = entity.getComponent(FurnitureStockpileComponent.class);
		if (component == null || !isInTradeDepot(entity, gameContext)) {
			return;
		}
		ItemType depotContents = tagProcessingUtils.itemTypeDictionary.getByName(TRADE_DEPOT_CONTENTS);
		if (depotContents == null) {
			return;
		}
		StockpileSettings settings = component.getStockpileSettings();
		if (settings.getEnabledItemTypes().size() == 1 && settings.getEnabledItemTypes().contains(depotContents)) {
			return; // already set, and this runs whenever the chest is drawn again
		}
		settings.clearAll();
		tagProcessingUtils.stockpileComponentUpdater.toggleItem(settings, depotContents, true, true, true);
		settings.addRestriction(depotContents);
	}

	@Override
	public void apply(Entity entity, TagProcessingUtils tagProcessingUtils, MessageDispatcher messageDispatcher, GameContext gameContext) {
		if (EntityType.FURNITURE == entity.getType() && entity.getComponent(FurnitureStockpileComponent.class) == null) {
			new InventoryItemsUnallocatedTag().apply(entity, tagProcessingUtils, messageDispatcher, gameContext);

			StockpileComponentUpdater stockpileComponentUpdater = tagProcessingUtils.stockpileComponentUpdater;


			int maxQuantity = 0;
			if (args.size() > 0) {
				maxQuantity = Integer.parseInt(args.get(0));
			}
			FurnitureStockpile furnitureStockpile = new FurnitureStockpile();
			furnitureStockpile.setMaxQuantity(maxQuantity);

			// Placed into a stockpile which has already been set up, so match it rather than making
			// the player repeat the same choices for every chest they drop in
			StockpileSettings roomSettings = args.size() <= 1 ? settingsOfContainingRoom(entity, gameContext) : null;
			final StockpileSettings stockpileSettings = roomSettings == null ? new StockpileSettings() : roomSettings.clone();
			if (args.size() <= 1 && roomSettings == null) {
				// No restrictions given, so it holds anything; the player can still switch entries off.
				for (ItemType itemType : tagProcessingUtils.itemTypeDictionary.getAll()) {
					// A handful of item types have no stockpile group, so don't ask for the parent to
					// be kept in step for those
					boolean hasParentGroup = itemType.getStockpileGroup() != null;
					stockpileComponentUpdater.toggleItem(stockpileSettings, itemType, true, hasParentGroup, true);
				}
			}
			for (int i = 1; i < args.size(); i++) {
				String restrictionArgumentString = args.get(i);
				Matcher isMacroMatcher = IS_MACRO_PATTERN.matcher(restrictionArgumentString);
				Matcher itemTypeMatcher = ITEM_TYPE_PATTERN.matcher(restrictionArgumentString);
				Predicate<ItemType> predicate = itemType -> false;
				if (isMacroMatcher.matches()) {
					String macroName = isMacroMatcher.group(1);
					predicate = switch (macroName) {
						case "ARMOUR" -> itemType -> itemType.getDefenseInfo() != null && DefenseType.ARMOR == itemType.getDefenseInfo().getType();
						case "SHIELD" -> itemType -> itemType.getDefenseInfo() != null && DefenseType.SHIELD == itemType.getDefenseInfo().getType();
						case "WEAPON" -> itemType -> itemType.getWeaponInfo() != null;
						default -> itemType -> false;
					};
				} else if (itemTypeMatcher.matches()) {
					String itemTypeName = itemTypeMatcher.group(1);
					predicate = itemType -> itemTypeName.equalsIgnoreCase(itemType.getItemTypeName());
				}
				tagProcessingUtils.itemTypeDictionary.getAll().stream()
								.filter(predicate)
								.forEach(itemType -> {
									stockpileComponentUpdater.toggleItem(stockpileSettings, itemType, true, true, true);
									stockpileSettings.addRestriction(itemType);
								});
			}

			FurnitureStockpileComponent component = new FurnitureStockpileComponent(stockpileSettings, furnitureStockpile);
			component.init(entity, messageDispatcher, gameContext);
			entity.addComponent(component);
		}

		// Outside the block above on purpose: tags are applied again whenever the furniture is redrawn,
		// so a chest already standing in a depot in a saved game is put right without being rebuilt
		restrictToDepotContents(entity, tagProcessingUtils, gameContext);
	}
}
