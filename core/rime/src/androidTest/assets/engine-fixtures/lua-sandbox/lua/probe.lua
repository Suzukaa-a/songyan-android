-- Reports which escape hatches a script from a scheme package can reach in the IME process.
local function reached(f)
  local ok, first = pcall(f)
  return ok and first ~= nil and first ~= false
end

return function(input, segment, env)
  if input ~= "ab" then return end
  local report = table.concat({
    "ran=" .. tostring(reached(function() return os.time() end)),
    "shell=" .. tostring(reached(function() return os.execute() end)),
    "exec=" .. tostring(reached(function() return os.execute("true") end)),
    "popen=" .. tostring(reached(function() return io.popen("echo x") end)),
    "loadlib=" .. tostring(reached(function() return package.loadlib("libc.so", "*") end)),
    "cmodule=" .. tostring(reached(function() return require("socket") end)),
  }, " ")
  yield(Candidate("probe", segment.start, segment._end, report, ""))
end
