import minecraftData from "minecraft-data";

export const version = "1.21.11";

// In 1.21.11 display belongs to EACH attribute entry, not the whole component.
const slotComponent = structuredClone(
  minecraftData(version).protocol.types.SlotComponent,
);
const modifier = slotComponent[1][1].type[1].fields.attribute_modifiers;
const [attributes, display] = modifier[1];
attributes.type[1].type[1].push(display);
slotComponent[1][1].type[1].fields.attribute_modifiers =
  "qa_attribute_modifiers";

// minecraft-data 3.109.0 still models the old optional-NBT format. The pinned
// 1.21.11 ServerboundCustomClickActionPacket uses lengthPrefixed(optionalTagCodec).
export const customPackets = {
  1.21: {
    types: {
      SlotComponent: slotComponent,
      qa_attribute_modifiers: ["container", [attributes]],
      packet_common_custom_click_action: [
        "container",
        [
          { name: "id", type: "string" },
          { name: "nbt", type: "ByteArray" },
        ],
      ],
    },
  },
};
