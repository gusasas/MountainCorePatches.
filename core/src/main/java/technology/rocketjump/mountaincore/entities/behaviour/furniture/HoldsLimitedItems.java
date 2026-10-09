package technology.rocketjump.mountaincore.entities.behaviour.furniture;

/**
 * Furniture which only ever holds so many items, so the panel can draw that many slots
 * rather than padding the row out to something the player cannot fill.
 */
public interface HoldsLimitedItems {

	int getItemCapacity();

}
