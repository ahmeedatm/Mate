-- Mate pour Cyberpunk 2077 : écran cyberware. Bloc lent cyberware.json (corps de V, capacité, slots par zone, implants
-- possédés) et ordre `equip`, qui fait ce que fait le charcudoc du jeu (ripperdoc.script, EquipCyberware) : mêmes
-- contrôles que CheckIfCanEquip, UnequipRequest sur le slot occupé, puis EquipRequest. Chargé par logic.lua.
-- Contrat : docs/specs/2026-11-14-cyberpunk-cyberware-design.md.
return function(h)
  local try, itemInfo = h.try, h.itemInfo
  local M = {}

  local AREAS = { "SystemReplacementCW", "FrontalCortexCW", "EyesCW", "ArmsCW", "HandsCW", "MusculoskeletalSystemCW",
    "NervousSystemCW", "CardiovascularSystemCW", "ImmuneSystemCW", "IntegumentarySystemCW", "LegsCW" }
  local IS_AREA = {}
  for _, a in ipairs(AREAS) do IS_AREA[a] = true end
  -- Zones qui changent les armes ou le mode de jeu : en lecture seule tant que le spike ne les a pas prouvées sûres.
  local CLOSED = { ArmsCW = true, SystemReplacementCW = true }
  -- Pose en attente de vérification : au plus 3 s, le jeu traite les requêtes à son rythme.
  local VERIFY_S = 3

  -- « gamedataEquipmentArea : EyesCW (12) » → « EyesCW ».
  local function word(e)
    local s = tostring(e)
    return string.match(s, ":%s*(%S+)") or s
  end

  local function es() return Game.GetScriptableSystemsContainer():Get(CName.new("EquipmentSystem")) end

  local function seed(id)
    local ok, s = pcall(function() return id.rng_seed end)
    if not ok or s == nil then ok, s = pcall(function() return id.rngSeed end) end
    return ok and tonumber(s) or 0
  end

  -- Clé d'un implant : id TweakDB et graine (deux exemplaires du même implant peuvent différer).
  local function key(id) return TDBID.ToStringDEBUG(ItemID.GetTDBID(id)) .. "#" .. tostring(seed(id)) end

  local function valid(id) return id ~= nil and ItemID.IsValid(id) end

  -- Zone d'un objet ; nil pour ce qui ne s'équipe pas (la plupart de l'inventaire), sans erreur.
  local function areaOf(id)
    local rec = TweakDBInterface.GetItemRecord(ItemID.GetTDBID(id))
    local ea = rec and rec:EquipArea()
    return ea and word(ea:Type()) or nil
  end

  -- Coût en capacité : exigence HumanityAvailable de l'implant, comme GetItemCapacityRequirement du jeu.
  local function cost(pl, id)
    local data = Game.GetTransactionSystem():GetItemData(pl, id)
    local reqs = RPGManager.GetEquipRequirements(pl, data)
    for i = 1, #(reqs or {}) do
      if word(reqs[i].statType) == "HumanityAvailable" then return tonumber(reqs[i].requiredValue) or 0 end
    end
    return 0
  end

  local function blocked(pl, id)
    return Game.GetTransactionSystem():HasTag(pl, CName.new("UnequipBlocked"), id) == true
  end

  local function stat(pl, name) return Game.GetStatsSystem():GetStatValue(pl:GetEntityID(), gamedataStatType[name]) end

  local function describe(pl, id, k)
    local info = itemInfo(pl, id)
    local rec = TweakDBInterface.GetItemRecord(ItemID.GetTDBID(id))
    local r = { key = key(id), tdb = info.tdb, name = info.name, quality = info.quality }
    r.cost = try(k .. ".cost", function() return cost(pl, id) end)
    r.blocked = try(k .. ".blocked", function() return blocked(pl, id) end) == true
    -- Icône : le nom de la pièce dans les atlas item_icons du jeu (IconPath = pièce de UIIcon.<nom>, vu au spike).
    r.icon = try(k .. ".icon", function() return rec:IconPath() end)
    return r
  end

  -- Slot de la zone, lu comme la page Perso (GetItemInEquipSlot prouvé au premier spike).
  local function inSlot(pd, area, i) return pd:GetItemInEquipSlot(area, i) end

  function M.block(pl)
    local pd = try("cw.pd", function() return es():GetPlayerData(pl) end)
    if not pd then return nil end
    local zones, equipped = {}, {}
    for _, a in ipairs(AREAS) do
      local area = gamedataEquipmentArea[a]
      -- `true` : avec les emplacements verrouillés (perk ou attribut manquant), affichés comme dans le jeu.
      local n = try("cw.n." .. a, function() return pd:GetNumberOfSlots(area, true) end) or 0
      local slots = {}
      for i = 0, n - 1 do
        local s = { locked = try("cw.lock." .. a .. i, function() return pd:IsSlotLocked(area, i) end) == true }
        local id = try("cw.id." .. a .. i, function() return inSlot(pd, area, i) end)
        if valid(id) then
          s.item = try("cw.item." .. a .. i, function() return describe(pl, id, "cw." .. a .. i) end)
          if s.item then equipped[s.item.key] = true end
        end
        slots[#slots + 1] = s
      end
      zones[a] = slots
    end
    local owned = {}
    local _, list = Game.GetTransactionSystem():GetItemList(pl)
    for i = 1, #(list or {}) do
      local id = list[i]:GetID()
      local a = try("cw.own." .. i, function() return areaOf(id) end)
      if a and IS_AREA[a] and not equipped[key(id)] then
        local d = try("cw.owned." .. i, function() return describe(pl, id, "cw.owned." .. i) end)
        if d then d.area = a; owned[#owned + 1] = d end
      end
    end
    local gender = try("cw.gender", function() return tostring(pl:GetResolvedGenderName()) end)
    return {
      v = 1,
      body = gender and string.find(gender, "Female") and "woman" or "man",
      capacity = {
        free = try("cw.free", function() return stat(pl, "HumanityAvailable") end),
        used = try("cw.used", function() return stat(pl, "HumanityAllocated") end),
        max = try("cw.max", function() return stat(pl, "HumanityTotalMaxValue") end),
      },
      closed = { "ArmsCW", "SystemReplacementCW" },
      zones = zones,
      owned = owned,
    }
  end

  -- Combat, scène (tiers 2 à 5, comme BlockedBySceneTier du jeu) ou véhicule : pas de pose.
  local function busy(pl)
    if pl:IsInCombat() then return true end
    local psm = GetAllBlackboardDefs().PlayerStateMachine
    local tier = Game.GetBlackboardSystem():GetLocalInstanced(pl:GetEntityID(), psm):GetInt(psm.HighLevel)
    if tier >= 2 and tier <= 5 then return true end
    return Game.GetMountedVehicle(pl) ~= nil
  end

  local function findOwned(pl, k)
    local _, list = Game.GetTransactionSystem():GetItemList(pl)
    for i = 1, #(list or {}) do
      local id = list[i]:GetID()
      if key(id) == k then return id end
    end
    return nil
  end

  local function request(cls, fields)
    local r = cls.new()
    for f, v in pairs(fields) do r[f] = v end
    es():QueueRequest(r)
  end

  -- Pose `c.item` dans `c.slot` de `c.area`. Rend une fonction de vérification : le jeu traite les requêtes plus tard,
  -- l'ack part quand le slot porte l'implant (ou après VERIFY_S : l'ancien est remis, `echec`).
  function M.equip(c)
    local pl = Game.GetPlayer()
    local a = tostring(c.area)
    if not IS_AREA[a] then error("mauvaise-zone", 0) end
    if CLOSED[a] then error("zone-fermee", 0) end
    local ok, b = pcall(busy, pl)
    if not ok or b then error("indisponible", 0) end
    local area = gamedataEquipmentArea[a]
    local pd = es():GetPlayerData(pl)
    local slot = tonumber(c.slot)
    if not slot or slot % 1 ~= 0 or slot < 0 or slot >= pd:GetNumberOfSlots(area, true) then error("slot-verrouille", 0) end
    if pd:IsSlotLocked(area, slot) then error("slot-verrouille", 0) end
    local id = findOwned(pl, tostring(c.item))
    if not id then error("non-possede", 0) end
    if areaOf(id) ~= a then error("mauvaise-zone", 0) end
    local cur = inSlot(pd, area, slot)
    local hasCur = valid(cur)
    -- Déjà posé (ordre rejoué, double appui) : rien à faire.
    if hasCur and key(cur) == key(id) then return "ok" end
    if hasCur and blocked(pl, cur) then error("bloque", 0) end
    local need = cost(pl, id) - (hasCur and cost(pl, cur) or 0)
    if need > (tonumber(stat(pl, "HumanityAvailable")) or 0) then error("capacite", 0) end
    if hasCur then request(UnequipRequest, { owner = pl, areaType = area, slotIndex = slot }) end
    request(EquipRequest, { owner = pl, itemID = id, slotIndex = slot, addToInventory = false })
    local want, start = key(id), os.time()
    return function()
      local now = inSlot(pd, area, slot)
      if valid(now) and key(now) == want then return "ok" end
      if os.time() - start < VERIFY_S then return nil end
      -- Pas posé : l'ancien revient s'il a été retiré, comme si rien ne s'était passé.
      if hasCur and not valid(now) then pcall(request, EquipRequest, { owner = pl, itemID = cur, slotIndex = slot, addToInventory = false }) end
      error("echec", 0)
    end
  end

  return M
end
