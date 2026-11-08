(function () {
    const box = document.getElementById('chat');
    if (!box) return;
    const id = box.dataset.itinerary;
    const log = document.getElementById('chat-log');
    const form = document.getElementById('chat-form');
    const input = document.getElementById('chat-input');
    let loaded = false;

    // textContent keeps model output from being treated as html
    function add(role, text) {
        const div = document.createElement('div');
        div.className = 'msg ' + (role === 'user' ? 'user' : 'model');
        div.textContent = text;
        log.appendChild(div);
        log.scrollTop = log.scrollHeight;
        return div;
    }

    function open() {
        box.classList.remove('closed');
        input.focus();
        if (loaded) return;
        loaded = true;
        if (box.dataset.ready !== 'true') {
            add('model', 'The chat assistant needs GEMINI_CHAT_API_KEY to be set on the server.');
        }
        fetch('/api/itineraries/' + id + '/chat')
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (msgs) { msgs.forEach(function (m) { add(m.role, m.content); }); })
            .catch(function () { });
    }

    document.getElementById('chat-toggle').addEventListener('click', open);
    document.getElementById('chat-close').addEventListener('click', function () { box.classList.add('closed'); });

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        const q = input.value.trim();
        if (!q) return;
        add('user', q);
        input.value = '';
        const pending = add('model', '...');
        form.querySelector('button').disabled = true;
        fetch('/api/itineraries/' + id + '/chat', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({message: q})
        })
            .then(function (r) { return r.json(); })
            .then(function (data) { pending.textContent = data.reply || data.error || 'No answer.'; })
            .catch(function () { pending.textContent = 'Could not reach the server.'; })
            .finally(function () { form.querySelector('button').disabled = false; input.focus(); });
    });
})();
