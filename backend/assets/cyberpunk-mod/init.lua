-- Chargeur Mate : garde le jeu ouvert pendant qu'on remplace logic.lua (reload.txt non vide = recharger).
local logic, err, acc = nil, nil, 0

local function charger()
  local f, e = loadfile("logic.lua")
  if not f then err = tostring(e); return end
  local ok, m = pcall(f)
  if ok then logic, err = m, nil else err = tostring(m) end
end

local function noter()
  local f = io.open("loader-error.txt", "w")
  if f then f:write(err or ""); f:close() end
end

registerForEvent("onInit", function() charger(); noter() end)

registerForEvent("onUpdate", function(dt)
  acc = acc + dt
  if acc >= 0.5 then
    acc = 0
    local r = io.open("reload.txt", "r")
    if r then
      local b = r:read("*a"); r:close()
      if b and b ~= "" then
        local w = io.open("reload.txt", "w"); if w then w:close() end
        charger(); noter()
      end
    end
  end
  if logic and logic.update then
    local ok, e = pcall(logic.update, dt)
    if not ok then err = tostring(e); noter() end
  end
end)
