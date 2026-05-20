reduce = function (keys, values, rereduce) {
    let max = values[0]
    for (let i = 1; i < values.length; i++) {
        const curr = values[i]
        if (curr[0] > max[0]) {
            max = curr
        } else if (curr[0] === max[0]) {
            if (curr[1] > max[1]) {
                max = curr
            }
        }
    }
    return max
}