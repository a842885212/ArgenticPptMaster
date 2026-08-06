const API = "/api/ppt-jobs";

const els = {
  form: document.getElementById("createForm"),
  semanticAck: document.getElementById("semanticAck"),
  sourceFile: document.getElementById("sourceFile"),
  sourceList: document.getElementById("sourceList"),
  createBtn: document.getElementById("createBtn"),
  createError: document.getElementById("createError"),
  jobPanel: document.getElementById("jobPanel"),
  jobId: document.getElementById("jobId"),
  jobStatus: document.getElementById("jobStatus"),
  analysisSummary: document.getElementById("analysisSummary"),
  analysisDl: document.getElementById("analysisDl"),
  progressDl: document.getElementById("progressDl"),
  confirmPanel: document.getElementById("confirmPanel"),
  confirmSummary: document.getElementById("confirmSummary"),
  rejectFeedback: document.getElementById("rejectFeedback"),
  approveBtn: document.getElementById("approveBtn"),
  rejectBtn: document.getElementById("rejectBtn"),
  confirmError: document.getElementById("confirmError"),
  resumeBtn: document.getElementById("resumeBtn"),
  downloadLink: document.getElementById("downloadLink"),
  jobError: document.getElementById("jobError"),
};

/** @type {{ jobId?: string, statusRank: number, es?: EventSource, reconnectAttempt: number, planVersion?: number, planDigest?: string }} */
const state = { statusRank: -1, reconnectAttempt: 0 };

function extOf(name) {
  const i = name.lastIndexOf(".");
  return i >= 0 ? name.slice(i + 1).toLowerCase() : "";
}

function renderFileList(ul, files) {
  ul.innerHTML = "";
  for (const f of files) {
    const li = document.createElement("li");
    li.textContent = `${f.name} (${f.size} bytes)`;
    ul.appendChild(li);
  }
}

function validateLocal() {
  const errors = [];
  const files = [...(els.sourceFile.files || [])];
  if (files.length !== 1) {
    errors.push("请选择且仅选择一个源 PPTX");
  } else if (extOf(files[0].name) !== "pptx") {
    errors.push("源文件必须是 .pptx");
  }
  if (!els.semanticAck.checked) {
    errors.push("请先确认硬约束：内容与页序冻结，仅重做布局");
  }
  return errors;
}

function refreshCreateEnabled() {
  const errors = validateLocal();
  els.createBtn.disabled = errors.length > 0;
  if (errors.length && document.activeElement === els.createBtn) {
    show(els.createError, errors[0]);
  } else {
    hide(els.createError);
  }
}

function show(el, text) {
  el.hidden = false;
  if (text != null) el.textContent = text;
}

function hide(el) {
  el.hidden = true;
  el.textContent = "";
}

function fillDl(dl, entries) {
  dl.innerHTML = "";
  for (const [k, v] of entries) {
    if (v == null || v === "") continue;
    const dt = document.createElement("dt");
    dt.textContent = k;
    const dd = document.createElement("dd");
    dd.textContent = String(v);
    dl.append(dt, dd);
  }
}

const STATUS_RANK = {
  ACCEPTED: 0,
  PREPARING: 1,
  WAITING_CONFIRMATION: 2,
  RUNNING_AGENT: 3,
  EXPORTING: 4,
  COMPLETED: 5,
  FAILED: 5,
  CANCELLED: 5,
};

function contextData(job) {
  return job.confirmationPayload?.contextData || {};
}

function applyJob(job, { force = false } = {}) {
  const rank = STATUS_RANK[job.status] ?? 0;
  if (!force && rank < state.statusRank && job.status !== "FAILED") {
    return;
  }
  state.statusRank = rank;
  state.jobId = job.id;
  els.jobId.textContent = job.id;
  els.jobStatus.textContent = job.status;
  show(els.jobPanel);

  const analysis = job.beautifyAnalysis || {};
  const validation = analysis.outputValidation || {};
  fillDl(els.progressDl, [
    ["planStatus", analysis.planStatus],
    ["sourcePageCount", analysis.sourcePageCount],
    ["complexObjectAlertCount", analysis.complexObjectAlertCount],
    ["imageCount", analysis.imageCount],
    ["tableCount", analysis.tableCount],
    ["chartCount", analysis.chartCount],
    ["readbackStatus", validation.status],
    ["warningCount", validation.warningCount],
    ["errorCount", validation.errorCount],
  ]);

  if (analysis.analyzedReady) {
    show(els.analysisSummary);
    fillDl(els.analysisDl, [
      ["sourcePageCount", analysis.sourcePageCount],
      ["resolvedFormat", analysis.resolvedFormat],
      ["imageCount", analysis.imageCount],
      ["tableCount", analysis.tableCount],
      ["chartCount", analysis.chartCount],
      ["complexObjectAlertCount", analysis.complexObjectAlertCount],
      ["imageManifestReady", analysis.imageManifestReady],
    ]);
  }

  const waiting =
    job.status === "WAITING_CONFIRMATION" &&
    job.currentConfirmationId &&
    (job.confirmationPayload?.stage === "beautify_plan_confirmation" ||
      contextData(job).type === "beautify_pptx_plan");
  if (waiting) {
    show(els.confirmPanel);
    const ctx = contextData(job);
    state.planVersion = typeof ctx.version === "number" ? ctx.version : undefined;
    state.planDigest = typeof ctx.digest === "string" ? ctx.digest : undefined;
    els.confirmSummary.textContent = JSON.stringify(job.confirmationPayload, null, 2);
  } else {
    hide(els.confirmPanel);
  }

  if (job.resumable) {
    els.resumeBtn.hidden = false;
  } else {
    els.resumeBtn.hidden = true;
  }

  const downloadReady =
    job.status === "COMPLETED" &&
    (job.downloadReady === true ||
      validation.status === "PASSED" ||
      validation.status === "PASSED_WITH_WARNINGS" ||
      validation.status === "OK" ||
      validation.status === "SUCCESS");
  if (downloadReady) {
    els.downloadLink.hidden = false;
    els.downloadLink.href = `${API}/${job.id}/download`;
  } else {
    els.downloadLink.hidden = true;
  }

  if (job.status === "FAILED") {
    show(els.jobError, job.errorMessage || "任务失败");
  } else {
    hide(els.jobError);
  }
}

async function fetchJob(jobId) {
  const res = await fetch(`${API}/${jobId}`);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.message || `查询失败 (${res.status})`);
  }
  return res.json();
}

function connectSse(jobId) {
  if (state.es) {
    state.es.close();
  }
  const es = new EventSource(`${API}/${jobId}/events`);
  state.es = es;
  es.onmessage = async () => {
    try {
      const job = await fetchJob(jobId);
      applyJob(job);
      state.reconnectAttempt = 0;
      if (job.status === "COMPLETED" || job.status === "FAILED" || job.status === "CANCELLED") {
        es.close();
      }
    } catch (err) {
      show(els.jobError, err.message);
    }
  };
  es.onerror = () => {
    es.close();
    const attempt = ++state.reconnectAttempt;
    if (attempt > 6) {
      show(els.jobError, "事件流中断，请刷新或手动恢复");
      return;
    }
    const delay = Math.min(1000 * 2 ** (attempt - 1), 15000);
    setTimeout(async () => {
      try {
        const job = await fetchJob(jobId);
        applyJob(job, { force: true });
        if (job.status !== "COMPLETED" && job.status !== "FAILED" && job.status !== "CANCELLED") {
          connectSse(jobId);
        }
      } catch (err) {
        show(els.jobError, err.message);
      }
    }, delay);
  };
}

els.sourceFile.addEventListener("change", () => {
  const files = [...(els.sourceFile.files || [])];
  if (files.length > 1) {
    show(els.createError, "只能选择一个源 PPTX");
    els.sourceFile.value = "";
    renderFileList(els.sourceList, []);
  } else {
    renderFileList(els.sourceList, files);
  }
  refreshCreateEnabled();
});

els.semanticAck.addEventListener("change", refreshCreateEnabled);

els.form.addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const errors = validateLocal();
  if (errors.length) {
    show(els.createError, errors[0]);
    return;
  }
  hide(els.createError);
  els.createBtn.disabled = true;
  const fd = new FormData();
  fd.append("files", els.sourceFile.files[0]);
  fd.append("workflowMode", "beautify-pptx");
  const projectName = document.getElementById("projectName").value.trim();
  const instruction = document.getElementById("instruction").value.trim();
  if (projectName) fd.append("projectName", projectName);
  if (instruction) fd.append("instruction", instruction);

  try {
    const res = await fetch(API, { method: "POST", body: fd });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      if (res.status === 503) {
        throw new Error(body.message || "美化功能未启用");
      }
      if (res.status === 403) {
        throw new Error(body.message || "当前身份不在灰度范围");
      }
      throw new Error(body.message || `创建失败 (${res.status})`);
    }
    state.statusRank = -1;
    applyJob(body, { force: true });
    connectSse(body.id);
  } catch (err) {
    show(els.createError, err.message);
  } finally {
    refreshCreateEnabled();
  }
});

async function submitConfirmation(approved) {
  hide(els.confirmError);
  try {
    const job = await fetchJob(state.jobId);
    const confirmationId = job.currentConfirmationId;
    if (!confirmationId) {
      throw new Error("当前没有待确认计划，已刷新状态");
    }
    const ctx = contextData(job);
    const version = typeof ctx.version === "number" ? ctx.version : state.planVersion;
    const digest = typeof ctx.digest === "string" ? ctx.digest : state.planDigest;
    if (version == null || !digest) {
      throw new Error("计划缺少 version/digest，请刷新后重试");
    }
    const res = await fetch(`${API}/${state.jobId}/confirm`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        confirmationId,
        approved,
        action: approved ? "APPROVE" : "REQUEST_REVISION",
        comment: approved ? null : els.rejectFeedback.value.trim() || null,
        answers: {
          planVersion: version,
          planDigest: digest,
        },
      }),
    });
    const body = await res.json().catch(() => ({}));
    if (res.status === 409 || res.status === 400) {
      show(els.confirmError, body.message || "确认冲突，请查看最新计划 version/digest");
      applyJob(await fetchJob(state.jobId), { force: true });
      return;
    }
    if (!res.ok) {
      throw new Error(body.message || `确认失败 (${res.status})`);
    }
    applyJob(body, { force: true });
  } catch (err) {
    show(els.confirmError, err.message);
  }
}

els.approveBtn.addEventListener("click", () => submitConfirmation(true));
els.rejectBtn.addEventListener("click", () => submitConfirmation(false));

els.resumeBtn.addEventListener("click", async () => {
  hide(els.jobError);
  try {
    const res = await fetch(`${API}/${state.jobId}/resume`, { method: "POST" });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.message || `恢复失败 (${res.status})`);
    applyJob(body, { force: true });
    connectSse(body.id);
  } catch (err) {
    show(els.jobError, err.message);
  }
});

refreshCreateEnabled();
