const state = {
    from: null,
    to: null,
    activeHours: 1,
    resourceFilter: '',
    refreshIntervalSeconds: 5,
    refreshTimer: null,
    loading: false,
};

const cards = document.querySelector('#cards');
const statusGraph = document.querySelector('#status-graph');
const message = document.querySelector('#message');
const rangeLabel = document.querySelector('#range-label');
const fromInput = document.querySelector('#from');
const toInput = document.querySelector('#to');
const resourceFilterInput = document.querySelector('#resource-filter');
const refreshIntervalInput = document.querySelector('#refresh-interval');
const failureDialog = document.querySelector('#failure-dialog');
const failureTitle = document.querySelector('#failure-title');
const failureSubtitle = document.querySelector('#failure-subtitle');
const failureList = document.querySelector('#failure-list');

document.querySelectorAll('.range').forEach((button) => {
    button.addEventListener('click', () => {
        document.querySelectorAll('.range').forEach((item) => item.classList.remove('active'));
        button.classList.add('active');
        setQuickRange(Number(button.dataset.hours));
        load();
    });
});

document.querySelector('#apply-custom').addEventListener('click', () => {
    document.querySelectorAll('.range').forEach((item) => item.classList.remove('active'));
    state.activeHours = null;
    state.from = new Date(fromInput.value).toISOString();
    state.to = new Date(toInput.value).toISOString();
    load();
});

document.querySelector('#refresh').addEventListener('click', () => {
    refreshNow();
});

resourceFilterInput.addEventListener('input', () => {
    state.resourceFilter = resourceFilterInput.value.trim().toLowerCase();
    load({ updateRange: false });
});

refreshIntervalInput.addEventListener('change', () => {
    state.refreshIntervalSeconds = Math.max(Number(refreshIntervalInput.value) || 0, 0);
    refreshIntervalInput.value = state.refreshIntervalSeconds;
    scheduleAutoRefresh();
});

document.querySelector('#failure-close').addEventListener('click', () => failureDialog.close());
failureDialog.addEventListener('click', (event) => {
    if (event.target === failureDialog) {
        failureDialog.close();
    }
});

setQuickRange(1);
load();
scheduleAutoRefresh();

function setQuickRange(hours) {
    state.activeHours = hours;
    const to = new Date();
    const from = new Date(to.getTime() - hours * 60 * 60 * 1000);
    state.from = from.toISOString();
    state.to = to.toISOString();
    fromInput.value = toDateTimeLocal(from);
    toInput.value = toDateTimeLocal(to);
}

async function load(options = {}) {
    if (state.loading) {
        return;
    }

    if (options.updateRange && state.activeHours) {
        setQuickRange(state.activeHours);
    }

    state.loading = true;
    showMessage('', false);
    rangeLabel.textContent = `${formatDateTimeMinute(state.from)} → ${formatDateTimeMinute(state.to)}`;

    try {
        const query = `from=${encodeURIComponent(state.from)}&to=${encodeURIComponent(state.to)}`;
        const availability = await fetchJson(apiUrl(`api/availability?${query}`));
        const filteredAvailability = filterResources(availability);

        renderCards(filteredAvailability);
        renderStatusGraph(filteredAvailability);
    } catch (error) {
        showMessage(error.message, true);
    } finally {
        state.loading = false;
    }
}

function refreshNow() {
    load({ updateRange: true });
}

function scheduleAutoRefresh() {
    if (state.refreshTimer) {
        clearInterval(state.refreshTimer);
        state.refreshTimer = null;
    }
    if (state.refreshIntervalSeconds > 0) {
        state.refreshTimer = setInterval(refreshNow, state.refreshIntervalSeconds * 1000);
    }
}

function filterResources(availability) {
    if (!state.resourceFilter) {
        return availability;
    }
    return availability.filter((item) => item.resourceName.toLowerCase().includes(state.resourceFilter));
}

async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Request failed: ${response.status} ${response.statusText}`);
    }
    return response.json();
}

function apiUrl(path) {
    return new URL(path, document.baseURI).toString();
}

function renderCards(availability) {
    cards.replaceChildren();
    const template = document.querySelector('#card-template');

    availability.forEach((item) => {
        const node = template.content.cloneNode(true);
        const latest = latestResourceStatus(item);
        const card = node.querySelector('.card');
        card.classList.add(`status-${statusClass(latest)}`);

        node.querySelector('.resource-name').textContent = item.resourceName;
        node.querySelector('.resource-target').textContent = `${(item.checks || []).length} checks`;
        const statusBadge = node.querySelector('.status-badge');
        statusBadge.textContent = statusText(latest);
        statusBadge.classList.add(statusClass(latest));
        node.querySelector('.availability').textContent = formatPercent(item.availabilityPercent);
        node.querySelector('.counts').textContent = `${item.onlineSamples}/${item.totalSamples} success`;

        renderCheckSummary(node.querySelector('.checks-summary'), item.checks || []);

        const sparkline = node.querySelector('.sparkline');
        const sparklineSamples = item.timeline || [];
        if (sparklineSamples.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'resource-target';
            empty.textContent = 'No resource samples in selected range.';
            sparkline.replaceWith(empty);
        } else {
            renderCardStatusLine(sparkline, item, sparklineSamples);
        }

        cards.append(node);
    });
}

function renderCardStatusLine(container, resource, samples) {
    container.replaceChildren();

    const fromMs = new Date(state.from).getTime();
    const toMs = new Date(state.to).getTime();
    const rangeMs = Math.max(toMs - fromMs, 1);
    const sortedSamples = [...samples].sort((left, right) => new Date(left.checkedAt) - new Date(right.checkedAt));
    const visibleRuns = buildVisibleStatusRuns(sortedSamples, fromMs, toMs);

    visibleRuns.forEach((run, index) => {
        const segmentStart = clamp(run.renderStartMs, fromMs, toMs);
        const segmentEnd = clamp(Math.max(run.renderEndMs, segmentStart + 1), fromMs, toMs);
        if (segmentEnd <= fromMs || segmentStart >= toMs) {
            return;
        }

        const leftPercent = ((segmentStart - fromMs) / rangeMs) * 100;
        const widthPercent = Math.max(((segmentEnd - segmentStart) / rangeMs) * 100, run.online ? 0 : 0.4);
        const capClasses = `${index === 0 ? 'cap-left' : ''} ${index === visibleRuns.length - 1 ? 'cap-right' : ''}`;

        const segment = document.createElement('span');
        segment.className = `sparkline-segment ${run.online ? 'online' : 'offline'} ${run.online ? '' : 'clickable'} ${capClasses}`;
        segment.style.left = `${leftPercent}%`;
        segment.style.width = `${widthPercent}%`;
        segment.title = run.online
                ? `${formatDateTime(run.samples[0].checkedAt)} - online`
                : `${formatDateTime(run.samples[0].checkedAt)} - offline. Click to see failed checks.`;
        if (!run.online) {
            segment.addEventListener('click', (event) => showFailureDialog(resource, sampleForSegmentClick(event, segment, run)));
        }
        container.append(segment);
    });
}

function renderCheckSummary(container, checks) {
    container.replaceChildren();

    if (checks.length === 0) {
        return;
    }

    checks.forEach((check) => {
        const row = document.createElement('div');
        row.className = 'check-summary-row';

        const status = document.createElement('span');
        status.className = `status-dot ${statusClass({successful: check.online})}`;

        const name = document.createElement('span');
        name.className = 'check-summary-name';
        name.textContent = `${check.checkName} (${check.checkType})`;

        row.append(status, name);
        container.append(row);
    });
}

function renderStatusGraph(availability) {
    statusGraph.replaceChildren();

    const fromMs = new Date(state.from).getTime();
    const toMs = new Date(state.to).getTime();
    const rangeMs = Math.max(toMs - fromMs, 1);

    const axis = document.createElement('div');
    axis.className = 'graph-axis';
    axis.innerHTML = `
        <span class="axis-start">${formatTime(state.from)}</span>
        <span class="axis-middle">${formatTime(midpoint(fromMs, toMs))}</span>
        <span class="axis-end">${formatTime(state.to)}</span>`;
    statusGraph.append(axis);

    availability.forEach((resource) => {
        const row = document.createElement('div');
        row.className = 'graph-row';

        const label = document.createElement('div');
        label.className = 'graph-label';
        label.innerHTML = `<strong>${escapeHtml(resource.resourceName)}</strong><span>${(resource.checks || []).length} checks</span>`;

        const track = document.createElement('div');
        track.className = 'graph-track';

        const samples = (resource.timeline || [])
                .sort((left, right) => new Date(left.checkedAt) - new Date(right.checkedAt));

        if (samples.length === 0) {
            const empty = document.createElement('span');
            empty.className = 'graph-empty';
            empty.textContent = 'No resource samples in range';
            track.append(empty);
        } else {
            const visibleRuns = buildVisibleStatusRuns(samples, fromMs, toMs);
            visibleRuns.forEach((run, index) => {
                const segmentStart = clamp(run.renderStartMs, fromMs, toMs);
                const segmentEnd = clamp(Math.max(run.renderEndMs, segmentStart + 1), fromMs, toMs);
                if (segmentEnd <= fromMs || segmentStart >= toMs) {
                    return;
                }

                const leftPercent = ((segmentStart - fromMs) / rangeMs) * 100;
                const widthPercent = Math.max(((segmentEnd - segmentStart) / rangeMs) * 100, run.online ? 0 : 0.25);
                const capClasses = `${index === 0 ? 'cap-left' : ''} ${index === visibleRuns.length - 1 ? 'cap-right' : ''}`;

                const segment = document.createElement('span');
                segment.className = `graph-segment ${run.online ? 'online' : 'offline'} ${run.online ? '' : 'clickable'} ${capClasses}`;
                segment.style.left = `${leftPercent}%`;
                segment.style.width = `${widthPercent}%`;
                segment.title = run.online
                        ? `${resource.resourceName}: online from ${formatDateTimeMinute(run.samples[0].checkedAt)}`
                        : `${resource.resourceName}: offline from ${formatDateTimeMinute(run.samples[0].checkedAt)}. Click to see failed checks.`;
                if (!run.online) {
                    segment.addEventListener('click', (event) => showFailureDialog(resource, sampleForSegmentClick(event, segment, run)));
                }
                track.append(segment);
            });
        }

        row.append(label, track);
        statusGraph.append(row);
    });
}

function buildVisibleStatusRuns(samples, fromMs, toMs) {
    const runs = buildStatusRuns(samples, toMs)
            .map((run) => ({
                ...run,
                renderStartMs: run.startMs,
                renderEndMs: run.endMs,
            }))
            .filter((run) => run.renderEndMs > fromMs && run.renderStartMs < toMs);

    if (runs.length > 0) {
        runs[runs.length - 1].renderEndMs = toMs;
    }

    return runs;
}

function buildStatusRuns(samples, toMs) {
    const runs = [];
    let currentRun = null;

    samples.forEach((sample, index) => {
        const checkedAtMs = new Date(sample.checkedAt).getTime();
        const nextMs = index + 1 < samples.length
                ? new Date(samples[index + 1].checkedAt).getTime()
                : toMs;

        if (!currentRun || currentRun.online !== sample.online) {
            if (currentRun) {
                runs.push(currentRun);
            }
            currentRun = {
                online: sample.online,
                startMs: checkedAtMs,
                endMs: nextMs,
                samples: [sample],
            };
        } else {
            currentRun.endMs = nextMs;
            currentRun.samples.push(sample);
        }
    });

    if (currentRun) {
        runs.push(currentRun);
    }

    return runs;
}

function sampleForSegmentClick(event, segment, run) {
    const rect = segment.getBoundingClientRect();
    const ratio = rect.width <= 0 ? 0 : clamp((event.clientX - rect.left) / rect.width, 0, 1);
    const clickedAtMs = run.startMs + (run.endMs - run.startMs) * ratio;
    return run.samples.reduce((nearest, sample) => {
        const nearestDistance = Math.abs(new Date(nearest.checkedAt).getTime() - clickedAtMs);
        const sampleDistance = Math.abs(new Date(sample.checkedAt).getTime() - clickedAtMs);
        return sampleDistance < nearestDistance ? sample : nearest;
    }, run.samples[0]);
}

function showFailureDialog(resource, sample) {
    const failedChecks = sample.failedChecks || [];
    failureTitle.textContent = resource.resourceName;
    failureSubtitle.textContent = `${formatDateTime(sample.checkedAt)} · ${failedChecks.length} failed checks`;
    failureList.replaceChildren();

    if (failedChecks.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'subtitle';
        empty.textContent = 'No failed check details were recorded for this sample.';
        failureList.append(empty);
    } else {
        failedChecks.forEach((check) => {
            const item = document.createElement('article');
            item.className = 'failure-item';
            item.innerHTML = `
                <div class="failure-item-header">
                    <span class="status-dot offline"></span>
                    <strong>${escapeHtml(check.checkName)} (${escapeHtml(check.checkType)})</strong>
                </div>
                <dl>
                    <dt>Target</dt><dd>${escapeHtml(check.target || 'N/A')}</dd>
                    <dt>Checked at</dt><dd>${formatDateTime(check.checkedAt)}</dd>
                    <dt>Duration</dt><dd>${escapeHtml(failurePeriodText(check))}</dd>
                    ${check.errorMessage ? `<dt>Error</dt><dd>${escapeHtml(check.errorMessage)}</dd>` : ''}
                    ${check.details ? `<dt>Details</dt><dd>${escapeHtml(check.details)}</dd>` : ''}
                </dl>`;
            failureList.append(item);
        });
    }

    failureDialog.showModal();
}

function failurePeriodText(check) {
    if (!check.failureStartedAt || !check.failureEndedAt) {
        return 'N/A';
    }
    if ((Number(check.failureDurationMs) || 0) <= 0 || check.failureStartedAt === check.failureEndedAt) {
        return '-';
    }
    return `${formatDuration(check.failureDurationMs || 0)} (${formatDateTime(check.failureStartedAt)} → ${formatDateTime(check.failureEndedAt)})`;
}

function showMessage(text, error) {
    message.hidden = !text;
    message.textContent = text;
    message.style.borderColor = error ? 'var(--red)' : 'var(--border)';
}

function formatPercent(value) {
    return value === null || value === undefined ? 'N/A' : `${value.toFixed(2)}%`;
}

function formatDateTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function formatDateTimeMinute(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return `${date.getFullYear()}.${pad(date.getMonth() + 1)}.${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function pad(value) {
    return String(value).padStart(2, '0');
}

function formatDuration(durationMs) {
    if (durationMs < 1000) {
        return `${durationMs} ms`;
    }
    const seconds = durationMs / 1000;
    if (seconds < 60) {
        return `${seconds.toFixed(seconds < 10 ? 2 : 1)} s`;
    }
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.round(seconds % 60);
    return `${minutes}m ${remainingSeconds}s`;
}

function midpoint(fromMs, toMs) {
    return new Date(fromMs + (toMs - fromMs) / 2).toISOString();
}

function statusClass(result) {
    if (!result || result.successful === null || result.successful === undefined) {
        return 'unknown';
    }
    return result.successful ? 'online' : 'offline';
}

function statusText(result) {
    if (!result || result.successful === null || result.successful === undefined) {
        return 'No data';
    }
    return result.successful ? 'Online' : 'Offline';
}

function latestResourceStatus(item) {
    return item.online === null || item.online === undefined ? null : {successful: item.online};
}

function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
}

function escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = value ?? '';
    return div.innerHTML;
}

function toDateTimeLocal(date) {
    const offsetMs = date.getTimezoneOffset() * 60 * 1000;
    return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16);
}
