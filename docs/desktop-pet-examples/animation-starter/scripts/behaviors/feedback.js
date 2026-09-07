export function feedback(element, type) { if (type === 'tap' || type === 'drag') { element.animate([{transform:'scale(1)'},{transform:'scale(.85)'},{transform:'scale(1)'}], {duration:350}); } }
